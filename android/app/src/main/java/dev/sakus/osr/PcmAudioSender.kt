// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
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
) {
    sealed class CaptureSource {
        data object Microphone : CaptureSource()
        class Playback(val mediaProjection: MediaProjection) : CaptureSource()
    }

    private val running = AtomicBoolean(false)
    private val gainPpm = AtomicInteger(1_000_000)
    private var worker: Thread? = null

    fun setGainPpm(value: Int) {
        gainPpm.set(value.coerceIn(0, 2_000_000))
    }

    fun start() {
        if (targets.isEmpty()) {
            status("No targets")
            return
        }
        if (!running.compareAndSet(false, true)) return
        worker = thread(name = "osr-pcm-sender") {
            runLoop()
        }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    private fun runLoop() {
        val sampleRate = 48_000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuffer <= 0) {
            status("AudioRecord unsupported")
            running.set(false)
            return
        }

        val frameBytes = sampleRate / 100 * 2 // 10ms, mono, s16
        val recordBufferSize = maxOf(minBuffer, frameBytes * 4)
        val payload = ByteArray(frameBytes)
        var packetSequence = 1L
        var frameSequence = 1L
        var volumeSequence = 1L
        var statusCounter = 0L
        val streamId = 1
        val epoch = 1L

        val recorder = try {
            createAudioRecord(sampleRate, channelConfig, audioFormat, recordBufferSize)
        } catch (error: Throwable) {
            status("Audio capture setup error: ${error.message}")
            running.set(false)
            return
        }

        val socket = DatagramSocket()

        try {
            recorder.startRecording()
            status("Sending ${captureSource.label()} PCM to ${targets.size} target(s)")

            while (running.get()) {
                val read = recorder.read(payload, 0, payload.size)
                if (read <= 0) continue

                val nowUs = System.nanoTime() / 1_000L
                val audioFrame = OsrProtocol.encodePcmAudioFrame(
                    streamId = streamId,
                    mediaTimeUs = nowUs,
                    frameSequence = frameSequence++,
                    sampleRateHz = sampleRate,
                    channels = 1,
                    frameDurationUs = 10_000,
                    pcmPayload = if (read == payload.size) payload else payload.copyOf(read),
                )
                val audioPacket = OsrProtocol.encodePacket(
                    OsrProtocol.KIND_AUDIO,
                    packetSequence++,
                    audioFrame,
                )

                val command = OsrProtocol.VolumeCommand(
                    streamId = streamId,
                    epoch = epoch,
                    sequence = volumeSequence++,
                    gainPpm = gainPpm.get(),
                    muted = false,
                    targetMediaTimeUs = 0,
                )
                val volumePayload = OsrProtocol.encodeVolumeCommand(command)
                val volumePacket = OsrProtocol.encodePacket(
                    OsrProtocol.KIND_VOLUME_COMMAND,
                    packetSequence++,
                    volumePayload,
                )

                var failed = 0
                for (target in targets) {
                    runCatching {
                        socket.send(DatagramPacket(audioPacket, audioPacket.size, target))
                        socket.send(DatagramPacket(volumePacket, volumePacket.size, target))
                    }.onFailure {
                        failed++
                    }
                }

                statusCounter++
                if (statusCounter % 100L == 0L || failed > 0) {
                    status(
                        "Fan-out ${captureSource.label()} targets=${targets.size} " +
                            "failed=$failed volume=${gainPpm.get() / 10_000}%",
                    )
                }
            }
        } catch (error: Throwable) {
            if (running.get()) status("Sender error: ${error.message}")
        } finally {
            running.set(false)
            runCatching { recorder.stop() }
            recorder.release()
            socket.close()
            val playbackSource = captureSource as? CaptureSource.Playback
            if (playbackSource != null) {
                runCatching { playbackSource.mediaProjection.stop() }
            }
            status("Sender stopped")
        }
    }

    private fun createAudioRecord(
        sampleRate: Int,
        channelConfig: Int,
        audioFormat: Int,
        recordBufferSize: Int,
    ): AudioRecord {
        return when (val source = captureSource) {
            CaptureSource.Microphone -> AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                recordBufferSize,
            )

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
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()
    }

    private fun CaptureSource.label(): String {
        return when (this) {
            CaptureSource.Microphone -> "microphone"
            is CaptureSource.Playback -> "device playback"
        }
    }

    companion object {
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
                    InetSocketAddress(host, port)
                }
                .distinctBy { "${it.hostString}:${it.port}" }
        }
    }
}
