// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private val playedFrames = AtomicLong(0)
    private var networkWorker: Thread? = null
    private var playbackWorker: Thread? = null

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var liveBuffer: LiveAudioBuffer? = null

    @Volatile
    private var activeSynchronizer: VolumeSynchronizer? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return

        val buffer = LiveAudioBuffer(
            targetFrames = quality.targetFrames,
            adaptive = quality.adaptiveLatency,
            minFrames = quality.minFrames,
            maxFrames = quality.maxFrames,
        )
        liveBuffer = buffer
        playedFrames.set(0)

        playbackWorker = thread(name = "osr-audio-playback", priority = Thread.MAX_PRIORITY) {
            playbackLoop(buffer)
        }
        networkWorker = thread(name = "osr-udp-receiver") {
            networkLoop(buffer)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        socket?.close()
        liveBuffer?.close()
        networkWorker?.interrupt()
        playbackWorker?.interrupt()
        networkWorker = null
        playbackWorker = null
        socket = null
        liveBuffer = null
    }

    private fun playbackLoop(buffer: LiveAudioBuffer) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }

        val sampleRate = 48_000
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val frameSamples = sampleRate / 100
        val minBufferBytes = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferBytes <= 0) {
            status("AudioTrack unsupported")
            running.set(false)
            buffer.close()
            return
        }

        val track = try {
            AudioTrack.Builder()
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
                .setBufferSizeInBytes(minBufferBytes)
                .build()
        } catch (error: Throwable) {
            status("Audio output setup error: ${error.message}")
            running.set(false)
            buffer.close()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val requestedStartFrames = frameSamples * quality.targetFrames.coerceAtLeast(1)
            runCatching {
                track.setStartThresholdInFrames(
                    requestedStartFrames.coerceAtMost(track.bufferCapacityInFrames),
                )
            }
        }

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
        activeSynchronizer = sync
        val toneProcessor = PcmToneProcessor(sampleRate, quality)

        try {
            track.play()
            status(
                "Listening UDP :$bindPort · target ${quality.targetFrames * 10}ms · " +
                    "AudioTrack ${track.bufferCapacityInFrames * 1_000L / sampleRate}ms",
            )

            while (running.get()) {
                val frame = buffer.take(20)
                if (frame == null) {
                    if (playedFrames.get() > 0) buffer.reportUnderrun()
                    continue
                }

                val pcm = frame.payload.copyOf()
                sync.applyGainToPcmS16Le(pcm, pcm.size)
                toneProcessor.processPcmS16Le(pcm)

                var offset = 0
                while (running.get() && offset < pcm.size) {
                    val written = track.write(
                        pcm,
                        offset,
                        pcm.size - offset,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    if (written <= 0) {
                        buffer.reportUnderrun()
                        break
                    }
                    offset += written
                }
                if (offset == pcm.size) playedFrames.incrementAndGet()
            }
        } catch (error: Throwable) {
            if (running.get()) status("Playback error: ${error.message}")
        } finally {
            activeSynchronizer = null
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            track.release()
            running.set(false)
            buffer.close()
        }
    }

    private fun networkLoop(buffer: LiveAudioBuffer) {
        var deviceVolumeEpoch = 0L
        var deviceVolumeSequence = 0L
        var lastAppliedNative: NativeVolumeState? = null
        var lastStatusAt = System.nanoTime()
        val discoveryResponder = LanDiscoveryResponder(deviceName, bindPort, status)
        discoveryResponder.start()

        val localSocket = try {
            DatagramSocket(bindPort).apply {
                soTimeout = 250
                receiveBufferSize = maxOf(receiveBufferSize, 64 * 1024)
            }
        } catch (error: Throwable) {
            status("UDP receiver setup error: ${error.message}")
            discoveryResponder.stop()
            running.set(false)
            buffer.close()
            return
        }
        socket = localSocket
        val packetBuffer = ByteArray(2048)

        try {
            while (running.get()) {
                val packet = DatagramPacket(packetBuffer, packetBuffer.size)
                try {
                    localSocket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    publishStats(buffer, lastStatusAt)?.let { lastStatusAt = it }
                    continue
                } catch (_: SocketException) {
                    if (running.get()) throw SocketException("UDP socket closed unexpectedly")
                    break
                }

                val decoded = OsrProtocol.decodePacket(packet.data, packet.length) ?: continue
                when (decoded.kind) {
                    OsrProtocol.KIND_VOLUME_COMMAND -> {
                        val command = OsrProtocol.decodeVolumeCommand(decoded.payload) ?: continue
                        activeSynchronizer?.applyParentCommand(command)
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
                        if (frame.sampleRateHz != 48_000 || frame.frameDurationUs != 10_000) continue
                        buffer.push(frame)
                    }
                }

                publishStats(buffer, lastStatusAt)?.let { lastStatusAt = it }
            }
        } catch (error: Throwable) {
            if (running.get()) status("Receiver error: ${error.message}")
        } finally {
            socket = null
            localSocket.close()
            discoveryResponder.stop()
            running.set(false)
            buffer.close()
            status("Receiver stopped")
        }
    }

    private fun publishStats(buffer: LiveAudioBuffer, lastStatusAt: Long): Long? {
        val now = System.nanoTime()
        if (now - lastStatusAt < 1_000_000_000L) return null
        val stats = buffer.stats()
        val nativeText = if (syncNativeVolume) " native-sync" else ""
        status(
            "Playing=${playedFrames.get()} queue=${stats.bufferedFrames * 10}ms " +
                "target=${stats.targetFrames * 10}ms lost=${stats.estimatedLostFrames} " +
                "stale=${stats.droppedStaleFrames} underruns=${stats.underruns}$nativeText",
        )
        return now
    }
}
