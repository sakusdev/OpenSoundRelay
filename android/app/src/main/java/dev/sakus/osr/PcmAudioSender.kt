// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PcmAudioSender(
    private val targets: List<InetSocketAddress>,
    private val captureSource: CaptureSource,
    private val status: (String) -> Unit,
    private val onStopped: () -> Unit = {},
    private val nativeVolumeProvider: (() -> NativeVolumeState?)? = null,
) {
    sealed class CaptureSource {
        data object Microphone : CaptureSource()
        class Playback(val mediaProjection: MediaProjection) : CaptureSource()
    }

    private val running = AtomicBoolean(false)
    private val gainPpm = AtomicInteger(1_000_000)
    private val bitrateKbps = AtomicInteger(DEFAULT_BITRATE_KBPS)
    private val nativeVolumeSyncEnabled = AtomicBoolean(false)
    private var worker: Thread? = null

    fun setGainPpm(value: Int) {
        gainPpm.set(value.coerceIn(0, 2_000_000))
    }

    fun setBitrateKbps(value: Int) {
        bitrateKbps.set(value.coerceIn(NativeOpusEncoder.MIN_BITRATE_KBPS, NativeOpusEncoder.MAX_BITRATE_KBPS))
    }

    fun setNativeVolumeSyncEnabled(enabled: Boolean) {
        nativeVolumeSyncEnabled.set(enabled)
    }

    fun start() {
        if (targets.isEmpty()) {
            status("No targets")
            return
        }
        if (!running.compareAndSet(false, true)) return
        worker = thread(name = "osr-opus-sender", priority = Thread.MAX_PRIORITY) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            try {
                runLoop()
            } finally {
                running.set(false)
                runCatching { onStopped() }
            }
        }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    private fun runLoop() {
        val playbackSource = captureSource as? CaptureSource.Playback
        val projectionCallback = playbackSource?.let {
            object : MediaProjection.Callback() {
                override fun onStop() {
                    status("Device playback capture ended")
                    stop()
                }
            }
        }

        try {
            if (playbackSource != null && projectionCallback != null) {
                playbackSource.mediaProjection.registerCallback(
                    projectionCallback,
                    Handler(Looper.getMainLooper()),
                )
            }
            captureAndSend()
        } catch (error: Throwable) {
            if (running.get()) status("Sender error: ${error.message}")
        } finally {
            running.set(false)
            if (playbackSource != null && projectionCallback != null) {
                runCatching { playbackSource.mediaProjection.unregisterCallback(projectionCallback) }
                runCatching { playbackSource.mediaProjection.stop() }
            }
            status("Sender stopped")
        }
    }

    private fun captureAndSend() {
        val sampleRate = SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuffer <= 0) {
            status("AudioRecord unsupported")
            running.set(false)
            return
        }

        val frameSamples = sampleRate * FRAME_DURATION_US / 1_000_000
        val frameBytes = frameSamples * 2
        val recordBufferSize = maxOf(minBuffer, frameBytes * 2)
        val payload = ByteArray(frameBytes)
        var packetSequence = 1L
        var frameSequence = 1L
        var volumeSequence = 1L
        var deviceVolumeSequence = 1L
        var statusCounter = 0L
        var encodeFailures = 0L
        val streamId = 1
        val epoch = System.currentTimeMillis().coerceAtLeast(1L)

        val recorder = try {
            createAudioRecord(sampleRate, channelConfig, audioFormat, recordBufferSize)
        } catch (error: Throwable) {
            status("Audio capture setup error: ${error.message}")
            running.set(false)
            return
        }

        val initialBitrate = bitrateKbps.get()
        val opusEncoder = NativeOpusEncoder.create(
            sampleRate = sampleRate,
            channelCount = 1,
            frameSamples = frameSamples,
            bitrateKbps = initialBitrate,
        )
        var appliedBitrate = initialBitrate
        val socket = DatagramSocket().apply {
            sendBufferSize = maxOf(sendBufferSize, 64 * 1024)
        }

        try {
            recorder.startRecording()
            val codecText = if (opusEncoder != null) "Opus ${appliedBitrate}kbps" else "PCM fallback 768kbps"
            status("Sending ${captureSource.label()} · $codecText · 5ms frames")

            while (running.get()) {
                val read = recorder.read(
                    payload,
                    0,
                    payload.size,
                    AudioRecord.READ_BLOCKING,
                )
                if (read != payload.size) continue

                val requestedBitrate = bitrateKbps.get()
                if (opusEncoder != null && requestedBitrate != appliedBitrate) {
                    if (opusEncoder.setBitrateKbps(requestedBitrate)) appliedBitrate = requestedBitrate
                }

                val encoded = opusEncoder?.encode(payload, read)
                val codec: Int
                val sampleFormat: Int
                val audioPayload: ByteArray
                if (opusEncoder != null) {
                    if (encoded == null) {
                        encodeFailures++
                        continue
                    }
                    codec = OsrProtocol.CODEC_OPUS
                    sampleFormat = OsrProtocol.SAMPLE_FORMAT_NONE
                    audioPayload = encoded
                } else {
                    codec = OsrProtocol.CODEC_PCM_S16LE
                    sampleFormat = OsrProtocol.SAMPLE_FORMAT_S16LE
                    audioPayload = payload.copyOf()
                }

                val nowUs = System.nanoTime() / 1_000L
                val audioFrame = OsrProtocol.encodeAudioFrame(
                    streamId = streamId,
                    mediaTimeUs = nowUs,
                    frameSequence = frameSequence,
                    sampleRateHz = sampleRate,
                    channels = 1,
                    codec = codec,
                    sampleFormat = sampleFormat,
                    frameDurationUs = FRAME_DURATION_US,
                    audioPayload = audioPayload,
                )
                val audioPacket = OsrProtocol.encodePacket(
                    OsrProtocol.KIND_AUDIO,
                    packetSequence++,
                    audioFrame,
                )

                val controlPackets = mutableListOf<ByteArray>()
                if (frameSequence % CONTROL_INTERVAL_FRAMES == 0L) {
                    val command = OsrProtocol.VolumeCommand(
                        streamId = streamId,
                        epoch = epoch,
                        sequence = volumeSequence++,
                        gainPpm = gainPpm.get(),
                        muted = false,
                        targetMediaTimeUs = 0,
                    )
                    controlPackets += OsrProtocol.encodePacket(
                        OsrProtocol.KIND_VOLUME_COMMAND,
                        packetSequence++,
                        OsrProtocol.encodeVolumeCommand(command),
                    )

                    if (nativeVolumeSyncEnabled.get()) {
                        val native = runCatching { nativeVolumeProvider?.invoke() }.getOrNull()
                        if (native != null) {
                            val deviceCommand = OsrProtocol.DeviceVolumeCommand(
                                epoch = epoch,
                                sequence = deviceVolumeSequence++,
                                volumePercent = native.percent,
                                muted = native.muted,
                            )
                            controlPackets += OsrProtocol.encodePacket(
                                OsrProtocol.KIND_DEVICE_VOLUME,
                                packetSequence++,
                                OsrProtocol.encodeDeviceVolumeCommand(deviceCommand),
                            )
                        }
                    }
                }

                var failed = 0
                for (target in targets) {
                    runCatching {
                        socket.send(DatagramPacket(audioPacket, audioPacket.size, target))
                        for (control in controlPackets) {
                            socket.send(DatagramPacket(control, control.size, target))
                        }
                    }.onFailure {
                        failed++
                    }
                }

                frameSequence++
                statusCounter++
                if (statusCounter % STATUS_INTERVAL_FRAMES == 0L || failed > 0) {
                    val native = if (nativeVolumeSyncEnabled.get()) {
                        runCatching { nativeVolumeProvider?.invoke() }.getOrNull()
                    } else {
                        null
                    }
                    status(
                        buildString {
                            append("${captureSource.label()} ")
                            append(if (opusEncoder != null) "Opus ${appliedBitrate}kbps" else "PCM fallback")
                            append(" · 5ms · targets=${targets.size} failed=$failed")
                            append(" stream=${gainPpm.get() / 10_000}%")
                            if (encodeFailures > 0) append(" encode-errors=$encodeFailures")
                            if (native != null) {
                                append(" native=${native.percent}%")
                                if (native.muted) append(" muted")
                            }
                        },
                    )
                }
            }
        } finally {
            opusEncoder?.close()
            runCatching { recorder.stop() }
            recorder.release()
            socket.close()
        }
    }

    private fun createAudioRecord(
        sampleRate: Int,
        channelConfig: Int,
        audioFormat: Int,
        recordBufferSize: Int,
    ): AudioRecord {
        return when (val source = captureSource) {
            CaptureSource.Microphone -> AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build(),
                )
                .setBufferSizeInBytes(recordBufferSize)
                .setPerformanceMode(AudioRecord.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            is CaptureSource.Playback -> createPlaybackAudioRecord(
                source.mediaProjection,
                sampleRate,
                recordBufferSize,
            )
        }
    }

    private fun createPlaybackAudioRecord(
        mediaProjection: MediaProjection,
        sampleRate: Int,
        recordBufferSize: Int,
    ): AudioRecord {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("Device playback capture requires Android 10+")
        }

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(recordBufferSize)
            .setPerformanceMode(AudioRecord.PERFORMANCE_MODE_LOW_LATENCY)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()
    }

    private fun CaptureSource.label(): String = when (this) {
        CaptureSource.Microphone -> "microphone"
        is CaptureSource.Playback -> "device playback"
    }

    companion object {
        const val DEFAULT_BITRATE_KBPS = 128
        const val FRAME_DURATION_US = 5_000
        private const val SAMPLE_RATE = 48_000
        private const val CONTROL_INTERVAL_FRAMES = 20L
        private const val STATUS_INTERVAL_FRAMES = 200L

        fun parseTargets(raw: String, defaultPort: Int): List<InetSocketAddress> {
            return raw
                .split(',', ';', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { value ->
                    val host: String
                    val port: Int
                    val lastColon = value.lastIndexOf(':')
                    if (lastColon > 0 && lastColon < value.lastIndex - 1) {
                        host = value.substring(0, lastColon).trim()
                        port = value.substring(lastColon + 1).trim().toIntOrNull() ?: return@mapNotNull null
                    } else {
                        host = value
                        port = defaultPort
                    }
                    if (port !in 1..65_535) return@mapNotNull null
                    InetSocketAddress(host, port)
                }
                .distinctBy { "${it.hostString}:${it.port}" }
        }
    }
}
