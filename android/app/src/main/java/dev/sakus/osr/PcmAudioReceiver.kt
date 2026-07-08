// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class PcmAudioReceiver(
    private val bindPort: Int,
    private val status: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = thread(name = "osr-pcm-receiver") {
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
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuffer <= 0) {
            status("AudioTrack unsupported")
            running.set(false)
            return
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate / 100 * 2 * 6))
            .build()

        val sync = VolumeSynchronizer(
            OsrProtocol.VolumeCommand(
                streamId = 1,
                epoch = 0,
                sequence = 0,
                gainPpm = 1_000_000,
                muted = false,
                targetMediaTimeUs = 0,
            ),
        )
        val jitterBuffer = PcmJitterBuffer(targetFrames = 3, maxFrames = 16)

        val socket = DatagramSocket(bindPort)
        socket.soTimeout = 500
        val packetBuffer = ByteArray(2048)
        val packet = DatagramPacket(packetBuffer, packetBuffer.size)

        try {
            track.play()
            status("Listening on UDP :$bindPort")

            while (running.get()) {
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }

                val decoded = OsrProtocol.decodePacket(packet.data, packet.length) ?: continue
                when (decoded.kind) {
                    OsrProtocol.KIND_VOLUME_COMMAND -> {
                        val command = OsrProtocol.decodeVolumeCommand(decoded.payload) ?: continue
                        val accepted = sync.applyParentCommand(command)
                        if (accepted) {
                            status("Synced volume ${command.gainPpm / 10_000}%")
                        }
                    }

                    OsrProtocol.KIND_AUDIO -> {
                        val frame = OsrProtocol.decodeAudioFrame(decoded.payload) ?: continue
                        if (frame.codec != 1 || frame.sampleFormat != 1 || frame.channels != 1) continue

                        for (readyFrame in jitterBuffer.push(frame)) {
                            val pcm = readyFrame.payload.copyOf()
                            sync.applyGainToPcmS16Le(pcm, pcm.size)
                            track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (running.get()) status("Receiver error: ${error.message}")
        } finally {
            running.set(false)
            socket.close()
            jitterBuffer.clear()
            runCatching { track.stop() }
            track.release()
            status("Receiver stopped")
        }
    }
}
