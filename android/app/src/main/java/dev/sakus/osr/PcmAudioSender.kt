// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PcmAudioSender(
    private val targetHost: String,
    private val targetPort: Int,
    private val status: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val gainPpm = AtomicInteger(1_000_000)
    private var worker: Thread? = null

    fun setGainPpm(value: Int) {
        gainPpm.set(value.coerceIn(0, 2_000_000))
    }

    fun start() {
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
        val streamId = 1
        val epoch = 1L

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            recordBufferSize,
        )

        val socket = DatagramSocket()
        val address = InetAddress.getByName(targetHost)

        try {
            recorder.startRecording()
            status("Sending PCM to $targetHost:$targetPort")

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
                val audioPacket = OsrProtocol.encodePacket(OsrProtocol.KIND_AUDIO, packetSequence++, audioFrame)
                socket.send(DatagramPacket(audioPacket, audioPacket.size, address, targetPort))

                val command = OsrProtocol.VolumeCommand(
                    streamId = streamId,
                    epoch = epoch,
                    sequence = volumeSequence++,
                    gainPpm = gainPpm.get(),
                    muted = false,
                    targetMediaTimeUs = 0,
                )
                val volumePayload = OsrProtocol.encodeVolumeCommand(command)
                val volumePacket = OsrProtocol.encodePacket(OsrProtocol.KIND_VOLUME_COMMAND, packetSequence++, volumePayload)
                socket.send(DatagramPacket(volumePacket, volumePacket.size, address, targetPort))
            }
        } catch (error: Throwable) {
            if (running.get()) status("Sender error: ${error.message}")
        } finally {
            running.set(false)
            runCatching { recorder.stop() }
            recorder.release()
            socket.close()
            status("Sender stopped")
        }
    }
}
