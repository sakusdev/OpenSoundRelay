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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PcmAudioSender(
    private val targets: List<InetSocketAddress>,
    private val captureSource: CaptureSource,
    private val qualityProfile: AudioQualityProfile = AudioQualityProfile.Balanced,
    private val status: (String) -> Unit,
    private val onStopped: () -> Unit = {},
) {
    sealed class CaptureSource {
        data object Microphone : CaptureSource()
        class Playback(val mediaProjection: MediaProjection) : CaptureSource()
    }

    private data class CaptureSpec(
        val sampleRateHz: Int,
        val channels: Int,
        val frameDurationUs: Int,
    ) {
        val inputChannelMask: Int
            get() = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

        val frameBytes: Int
            get() = ((sampleRateHz.toLong() * frameDurationUs * channels * 2) / 1_000_000L).toInt()
    }

    private data class CaptureSession(
        val recorder: AudioRecord,
        val spec: CaptureSpec,
    )

    private val running = AtomicBoolean(false)
    private val deviceVolumePpm = AtomicInteger(NativeVolumeController.ONE_MILLION)
    private var worker: Thread? = null

    fun setDeviceVolumePpm(value: Int) {
        deviceVolumePpm.set(value.coerceIn(0, NativeVolumeController.ONE_MILLION))
    }

    @Deprecated("Use setDeviceVolumePpm")
    fun setGainPpm(value: Int) = setDeviceVolumePpm(value)

    fun start() {
        if (targets.isEmpty()) {
            status("No targets")
            return
        }
        if (!running.compareAndSet(false, true)) return
        worker = thread(name = "osr-pcm-sender") {
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
            if (running.get()) status("Sender error: ${error.message ?: error.javaClass.simpleName}")
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
        val session = createCaptureSession() ?: run {
            status("No supported audio capture format")
            return
        }
        val recorder = session.recorder
        val spec = session.spec
        val payload = ByteArray(spec.frameBytes)
        val socket = DatagramSocket()
        var packetSequence = 1L
        var frameSequence = 1L
        var volumeSequence = 1L
        var lastSentVolume = -1
        var statusCounter = 0L
        val streamId = 1
        val epoch = System.currentTimeMillis().coerceAtLeast(1L)
        val framesPerStatus = (1_000_000L / spec.frameDurationUs).coerceAtLeast(1L)

        try {
            recorder.startRecording()
            status(
                "Sending ${qualityProfile.displayName} ${spec.sampleRateHz / 1_000} kHz " +
                    "${if (spec.channels == 2) "stereo" else "mono"} to ${targets.size} device(s)",
            )

            while (running.get()) {
                if (!readFullFrame(recorder, payload)) break

                val nowUs = System.nanoTime() / 1_000L
                val audioFrame = OsrProtocol.encodePcmAudioFrame(
                    streamId = streamId,
                    mediaTimeUs = nowUs,
                    frameSequence = frameSequence,
                    sampleRateHz = spec.sampleRateHz,
                    channels = spec.channels,
                    frameDurationUs = spec.frameDurationUs,
                    pcmPayload = payload,
                )
                val audioPacket = OsrProtocol.encodePacket(
                    OsrProtocol.KIND_AUDIO,
                    packetSequence++,
                    audioFrame,
                )

                val currentVolume = deviceVolumePpm.get()
                val sendVolume = currentVolume != lastSentVolume ||
                    frameSequence % VOLUME_HEARTBEAT_FRAMES == 1L
                val volumePacket = if (sendVolume) {
                    val command = OsrProtocol.VolumeCommand(
                        streamId = streamId,
                        epoch = epoch,
                        sequence = volumeSequence++,
                        gainPpm = currentVolume,
                        muted = currentVolume == 0,
                        targetMediaTimeUs = 0,
                    )
                    lastSentVolume = currentVolume
                    OsrProtocol.encodePacket(
                        OsrProtocol.KIND_DEVICE_VOLUME_COMMAND,
                        packetSequence++,
                        OsrProtocol.encodeVolumeCommand(command),
                    )
                } else {
                    null
                }

                var failed = 0
                for (target in targets) {
                    runCatching {
                        socket.send(DatagramPacket(audioPacket, audioPacket.size, target))
                        if (volumePacket != null) {
                            socket.send(DatagramPacket(volumePacket, volumePacket.size, target))
                        }
                    }.onFailure {
                        failed++
                    }
                }

                frameSequence++
                statusCounter++
                if (statusCounter % framesPerStatus == 0L || failed > 0) {
                    status(
                        "${qualityProfile.displayName} fan-out · ${targets.size} device(s) · " +
                            "volume ${currentVolume / 10_000}% · failed $failed",
                    )
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            socket.close()
        }
    }

    private fun readFullFrame(recorder: AudioRecord, buffer: ByteArray): Boolean {
        var offset = 0
        while (running.get() && offset < buffer.size) {
            val read = recorder.read(buffer, offset, buffer.size - offset, AudioRecord.READ_BLOCKING)
            when {
                read > 0 -> offset += read
                read == 0 -> continue
                else -> error("AudioRecord read failed ($read)")
            }
        }
        return offset == buffer.size
    }

    private fun createCaptureSession(): CaptureSession? {
        val requested = CaptureSpec(
            sampleRateHz = qualityProfile.sampleRateHz,
            channels = qualityProfile.requestedChannels,
            frameDurationUs = qualityProfile.frameDurationUs,
        )
        val candidates = buildList {
            add(requested)
            if (requested.channels == 2) add(requested.copy(channels = 1))
            if (requested.sampleRateHz != 48_000) {
                add(requested.copy(sampleRateHz = 48_000, channels = 1))
            }
        }.distinct()

        for (spec in candidates) {
            val minBuffer = AudioRecord.getMinBufferSize(
                spec.sampleRateHz,
                spec.inputChannelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0 || spec.frameBytes <= 0) continue
            val bufferSize = maxOf(minBuffer, spec.frameBytes * 4)
            val recorder = runCatching { createAudioRecord(spec, bufferSize) }.getOrNull() ?: continue
            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                if (spec != requested) {
                    status(
                        "${qualityProfile.displayName} capture fallback: " +
                            "${spec.sampleRateHz / 1_000} kHz ${if (spec.channels == 2) "stereo" else "mono"}",
                    )
                }
                return CaptureSession(recorder, spec)
            }
            recorder.release()
        }
        return null
    }

    private fun createAudioRecord(spec: CaptureSpec, bufferSize: Int): AudioRecord {
        return when (val source = captureSource) {
            CaptureSource.Microphone -> AudioRecord(
                MediaRecorder.AudioSource.MIC,
                spec.sampleRateHz,
                spec.inputChannelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )

            is CaptureSource.Playback -> createPlaybackAudioRecord(
                mediaProjection = source.mediaProjection,
                spec = spec,
                bufferSize = bufferSize,
            )
        }
    }

    private fun createPlaybackAudioRecord(
        mediaProjection: MediaProjection,
        spec: CaptureSpec,
        bufferSize: Int,
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
            .setSampleRate(spec.sampleRateHz)
            .setChannelMask(spec.inputChannelMask)
            .build()

        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()
    }

    companion object {
        private const val VOLUME_HEARTBEAT_FRAMES = 50L

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
                        host = value.substring(0, lastColon).trim().removeSurrounding("[", "]")
                        port = value.substring(lastColon + 1).trim().toIntOrNull() ?: return@mapNotNull null
                    } else {
                        host = value.removeSurrounding("[", "]")
                        port = defaultPort
                    }
                    if (port !in 1..65_535) return@mapNotNull null
                    InetSocketAddress(host, port)
                }
                .distinctBy { "${it.hostString}:${it.port}" }
        }
    }
}
