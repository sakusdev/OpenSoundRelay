// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

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
    private val decodeFailures = AtomicLong(0)
    private val outputDrops = AtomicLong(0)
    private val frameDurationUs = AtomicLong(PcmAudioSender.FRAME_DURATION_US.toLong())
    private var networkWorker: Thread? = null
    private var playbackWorker: Thread? = null

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var liveBuffer: LiveAudioBuffer? = null

    @Volatile
    private var activeSynchronizer: VolumeSynchronizer? = null

    @Volatile
    private var activeSink: LiveAudioSink? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return

        val buffer = LiveAudioBuffer(
            targetFrames = quality.targetFrames,
            adaptive = quality.adaptiveLatency,
            minFrames = quality.minFrames,
            maxFrames = quality.maxFrames,
            stableFramesBeforeShrink = quality.stableFramesBeforeShrink,
        )
        liveBuffer = buffer
        playedFrames.set(0)
        decodeFailures.set(0)
        outputDrops.set(0)
        frameDurationUs.set(PcmAudioSender.FRAME_DURATION_US.toLong())

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
        val sink = try {
            LiveAudioSinkFactory.create(sampleRate, 1)
        } catch (error: Throwable) {
            status("Audio output setup error: ${error.message}")
            running.set(false)
            buffer.close()
            return
        }
        activeSink = sink
        val initialSinkStats = sink.stats()

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
        val opusDecoder = NativeOpusDecoder.create(
            sampleRate = sampleRate,
            channelCount = 1,
            maxFrameSamples = sampleRate * 60 / 1_000,
        )
        var lastSinkUnderruns = initialSinkStats.underruns

        try {
            status(
                "Listening UDP :$bindPort · ${initialSinkStats.backend} · " +
                    "target ${quality.targetFrames * PcmAudioSender.FRAME_DURATION_US / 1_000}ms",
            )

            while (running.get()) {
                val frame = buffer.take(8)
                if (frame == null) {
                    if (playedFrames.get() > 0) buffer.reportUnderrun()
                    continue
                }

                val pcm = when (frame.codec) {
                    OsrProtocol.CODEC_PCM_S16LE -> {
                        if (frame.sampleFormat != OsrProtocol.SAMPLE_FORMAT_S16LE) null else frame.payload.copyOf()
                    }

                    OsrProtocol.CODEC_OPUS -> opusDecoder?.decode(frame.payload)
                    else -> null
                }
                if (pcm == null) {
                    decodeFailures.incrementAndGet()
                    continue
                }

                sync.applyGainToPcmS16Le(pcm, pcm.size)
                toneProcessor.processPcmS16Le(pcm)

                if (sink.write(pcm)) {
                    playedFrames.incrementAndGet()
                } else {
                    outputDrops.incrementAndGet()
                }

                val sinkStats = sink.stats()
                if (sinkStats.underruns > lastSinkUnderruns) {
                    buffer.reportUnderrun()
                    lastSinkUnderruns = sinkStats.underruns
                }
            }
        } catch (error: Throwable) {
            if (running.get()) status("Playback error: ${error.message}")
        } finally {
            activeSynchronizer = null
            activeSink = null
            opusDecoder?.close()
            sink.close()
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
                receiveBufferSize = maxOf(receiveBufferSize, 128 * 1024)
            }
        } catch (error: Throwable) {
            status("UDP receiver setup error: ${error.message}")
            discoveryResponder.stop()
            running.set(false)
            buffer.close()
            return
        }
        socket = localSocket
        val packetBuffer = ByteArray(4096)

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
                        if (frame.channels != 1 || frame.sampleRateHz != 48_000) continue
                        if (frame.frameDurationUs !in 2_500..20_000) continue
                        val supported = when (frame.codec) {
                            OsrProtocol.CODEC_PCM_S16LE -> frame.sampleFormat == OsrProtocol.SAMPLE_FORMAT_S16LE
                            OsrProtocol.CODEC_OPUS -> frame.sampleFormat == OsrProtocol.SAMPLE_FORMAT_NONE
                            else -> false
                        }
                        if (!supported) continue
                        frameDurationUs.set(frame.frameDurationUs.toLong())
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
        val sinkStats = activeSink?.stats()
        val durationMs = (frameDurationUs.get() / 1_000L).coerceAtLeast(1L)
        val nativeText = if (syncNativeVolume) " native-sync" else ""
        val sinkText = sinkStats?.let {
            "${it.backend} hwq=${it.queuedFrames * 1_000L / it.sampleRate.coerceAtLeast(1)}ms " +
                "xruns=${it.underruns} sink-drop=${it.droppedInputFrames}"
        } ?: "output-starting"
        status(
            "Playing=${playedFrames.get()} queue=${stats.bufferedFrames * durationMs}ms " +
                "target=${stats.targetFrames * durationMs}ms lost=${stats.estimatedLostFrames} " +
                "stale=${stats.droppedStaleFrames} decode=${decodeFailures.get()} " +
                "out-drop=${outputDrops.get()} $sinkText$nativeText",
        )
        return now
    }
}
