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
    private val nativeVolumeController: NativeVolumeController? = null,
    private val syncNativeVolume: Boolean = false,
    private val quality: AudioQualitySettings = AudioQualitySettings(),
    private val deviceName: String = LanDiscovery.defaultDeviceName(),
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
        val frameBytes = sampleRate / 100 * 2
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
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(minBuffer, frameBytes * (quality.targetFrames + 6)))
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
        val jitterBuffer = PcmJitterBuffer(
            targetFrames = quality.targetFrames,
            adaptive = quality.adaptiveLatency,
        )
        val toneProcessor = PcmToneProcessor(sampleRate, quality)
        var deviceVolumeEpoch = 0L
        var deviceVolumeSequence = 0L
        var lastAppliedNative: NativeVolumeState? = null
        var receivedFrames = 0L
        var lastStatusAt = System.nanoTime()

        val discoveryResponder = LanDiscoveryResponder(deviceName, bindPort, status)
        discoveryResponder.start()

        val socket = DatagramSocket(bindPort)
        socket.soTimeout = 250
        val packetBuffer = ByteArray(2048)

        try {
            track.play()
            status("Listening on UDP :$bindPort · adaptive ${quality.targetFrames * 10}ms")

            while (running.get()) {
                val packet = DatagramPacket(packetBuffer, packetBuffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }

                val decoded = OsrProtocol.decodePacket(packet.data, packet.length) ?: continue
                when (decoded.kind) {
                    OsrProtocol.KIND_VOLUME_COMMAND -> {
                        val command = OsrProtocol.decodeVolumeCommand(decoded.payload) ?: continue
                        sync.applyParentCommand(command)
                    }

                    OsrProtocol.KIND_DEVICE_VOLUME -> {
                        val command = OsrProtocol.decodeDeviceVolumeCommand(decoded.payload) ?: continue
                        val newer = command.epoch > deviceVolumeEpoch ||
                            (command.epoch == deviceVolumeEpoch && command.sequence > deviceVolumeSequence)
                        if (newer) {
                            deviceVolumeEpoch = command.epoch
                            deviceVolumeSequence = command.sequence
                            val requested = NativeVolumeState(command.volumePercent, command.muted)
                            if (syncNativeVolume && requested != lastAppliedNative) {
                                runCatching { nativeVolumeController?.apply(requested) }
                                    .onSuccess { lastAppliedNative = requested }
                                    .onFailure { status("Native volume sync failed: ${it.message}") }
                            }
                        }
                    }

                    OsrProtocol.KIND_AUDIO -> {
                        val frame = OsrProtocol.decodeAudioFrame(decoded.payload) ?: continue
                        if (frame.codec != 1 || frame.sampleFormat != 1 || frame.channels != 1) continue

                        for (readyFrame in jitterBuffer.push(frame)) {
                            val pcm = readyFrame.payload.copyOf()
                            sync.applyGainToPcmS16Le(pcm, pcm.size)
                            toneProcessor.processPcmS16Le(pcm)
                            track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                            receivedFrames++
                        }
                    }
                }

                val now = System.nanoTime()
                if (now - lastStatusAt >= 1_000_000_000L) {
                    val stats = jitterBuffer.stats()
                    val nativeText = if (syncNativeVolume) " native-sync" else ""
                    status(
                        "Receiving frames=$receivedFrames buffer=${stats.bufferedFrames * 10}ms " +
                            "target=${stats.targetFrames * 10}ms lost=${stats.estimatedLostFrames} " +
                            "corrections=${stats.corrections}$nativeText",
                    )
                    lastStatusAt = now
                }
            }
        } catch (error: Throwable) {
            if (running.get()) status("Receiver error: ${error.message}")
        } finally {
            running.set(false)
            socket.close()
            discoveryResponder.stop()
            jitterBuffer.clear()
            runCatching { track.stop() }
            track.release()
            status("Receiver stopped")
        }
    }
}
