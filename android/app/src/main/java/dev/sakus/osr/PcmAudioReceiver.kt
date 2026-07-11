// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class PcmAudioReceiver(
    context: Context,
    private val bindPort: Int,
    private val latencyMode: LatencyMode = LatencyMode.Auto,
    private val status: (String) -> Unit,
    private val onStats: (ReceiverStats) -> Unit = {},
    private val onStopped: () -> Unit = {},
) {
    private data class OutputFormat(
        val sampleRateHz: Int,
        val channels: Int,
        val frameDurationUs: Int,
        val frameBytes: Int,
    )

    private val applicationContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val nativeVolume = NativeVolumeController(applicationContext)
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = thread(name = "osr-pcm-receiver") {
            try {
                runLoop()
            } finally {
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
        val streamGainSync = VolumeSynchronizer(initialCommand())
        val deviceVolumeSync = VolumeSynchronizer(initialCommand())
        var jitterBuffer = PcmJitterBuffer(latencyMode)
        var track: AudioTrack? = null
        var outputFormat: OutputFormat? = null
        var lastSource = "—"
        var framesSinceStats = 0

        val socket = DatagramSocket(bindPort)
        socket.soTimeout = 500
        val packetBuffer = ByteArray(MAX_PACKET_BYTES)
        val packet = DatagramPacket(packetBuffer, packetBuffer.size)

        try {
            status("Listening on UDP :$bindPort · ${latencyMode.displayName} latency")

            while (running.get()) {
                try {
                    packet.length = packetBuffer.size
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }

                val decoded = OsrProtocol.decodePacket(packet.data, packet.length) ?: continue
                when (decoded.kind) {
                    OsrProtocol.KIND_VOLUME_COMMAND -> {
                        val command = OsrProtocol.decodeVolumeCommand(decoded.payload) ?: continue
                        streamGainSync.applyParentCommand(command)
                    }

                    OsrProtocol.KIND_DEVICE_VOLUME_COMMAND -> {
                        val command = OsrProtocol.decodeVolumeCommand(decoded.payload) ?: continue
                        if (deviceVolumeSync.applyParentCommand(command)) {
                            nativeVolume.setPpm(command.gainPpm.coerceAtMost(NativeVolumeController.ONE_MILLION))
                            status("Native media volume synced to ${command.gainPpm / 10_000}%")
                        }
                    }

                    OsrProtocol.KIND_AUDIO -> {
                        val frame = OsrProtocol.decodeAudioFrame(decoded.payload) ?: continue
                        if (!isSupported(frame)) continue
                        val nextFormat = OutputFormat(
                            sampleRateHz = frame.sampleRateHz,
                            channels = frame.channels,
                            frameDurationUs = frame.frameDurationUs,
                            frameBytes = frame.payload.size,
                        )
                        if (outputFormat != nextFormat) {
                            releaseTrack(track)
                            track = createTrack(nextFormat)
                            outputFormat = nextFormat
                            jitterBuffer = PcmJitterBuffer(latencyMode)
                            status(
                                "Receiving ${nextFormat.sampleRateHz / 1_000} kHz " +
                                    "${if (nextFormat.channels == 2) "stereo" else "mono"}",
                            )
                        }

                        lastSource = packet.address?.hostAddress ?: packet.socketAddress.toString()
                        for (ready in jitterBuffer.push(frame)) {
                            val activeTrack = track ?: break
                            repeat(ready.missingFramesBefore) {
                                val silence = ByteArray(ready.frame.payload.size)
                                activeTrack.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                            }
                            val pcm = ready.frame.payload.copyOf()
                            streamGainSync.applyGainToPcmS16Le(pcm, pcm.size)
                            activeTrack.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                        }

                        framesSinceStats++
                        if (framesSinceStats >= STATS_EVERY_FRAMES) {
                            framesSinceStats = 0
                            val stats = jitterBuffer.stats()
                            onStats(
                                ReceiverStats(
                                    source = lastSource,
                                    format = "${frame.sampleRateHz / 1_000} kHz · " +
                                        "${if (frame.channels == 2) "stereo" else "mono"} · " +
                                        "${frame.frameDurationUs / 1_000} ms",
                                    jitterMs = stats.jitterMs,
                                    bufferMs = stats.targetLatencyMs,
                                    bufferedFrames = stats.bufferedFrames,
                                    lostFrames = stats.lostFrames,
                                    lateFrames = stats.lateFrames,
                                ),
                            )
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (running.get()) status("Receiver error: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            running.set(false)
            socket.close()
            jitterBuffer.clear()
            releaseTrack(track)
            status("Receiver stopped")
        }
    }

    private fun createTrack(format: OutputFormat): AudioTrack {
        val channelMask = if (format.channels == 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            format.sampleRateHz,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "AudioTrack does not support this format" }
        val bufferSize = maxOf(minBuffer, format.frameBytes * (latencyMode.minFrames + 4))
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(format.sampleRateHz)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(bufferSize)
            .build()
        require(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" }
        track.play()
        return track
    }

    private fun releaseTrack(track: AudioTrack?) {
        if (track == null) return
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        track.release()
    }

    private fun isSupported(frame: OsrProtocol.AudioFrame): Boolean {
        return frame.codec == 1 &&
            frame.sampleFormat == 1 &&
            frame.channels in 1..2 &&
            frame.sampleRateHz in 8_000..96_000 &&
            frame.frameDurationUs in 2_500..40_000 &&
            frame.payload.isNotEmpty()
    }

    private fun initialCommand(): OsrProtocol.VolumeCommand {
        return OsrProtocol.VolumeCommand(
            streamId = 1,
            epoch = 0,
            sequence = 0,
            gainPpm = NativeVolumeController.ONE_MILLION,
            muted = false,
            targetMediaTimeUs = 0,
        )
    }

    companion object {
        private const val MAX_PACKET_BYTES = 2_048
        private const val STATS_EVERY_FRAMES = 50
    }
}
