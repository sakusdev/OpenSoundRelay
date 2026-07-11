// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.io.Closeable

internal data class LiveAudioSinkStats(
    val backend: String,
    val underruns: Long,
    val droppedInputFrames: Long,
    val queuedFrames: Long,
    val capacityFrames: Long,
    val framesPerBurst: Long,
    val sampleRate: Int,
)

internal interface LiveAudioSink : Closeable {
    fun write(pcmS16Le: ByteArray): Boolean
    fun stats(): LiveAudioSinkStats
}

internal object LiveAudioSinkFactory {
    fun create(sampleRate: Int, channelCount: Int): LiveAudioSink {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && NativeAudioBridge.loaded) {
            NativeAaudioSink.create(sampleRate, channelCount)?.let { return it }
        }
        return AudioTrackLiveSink(sampleRate, channelCount)
    }
}

private class NativeAaudioSink private constructor(
    private var handle: Long,
    private val requestedSampleRate: Int,
) : LiveAudioSink {
    override fun write(pcmS16Le: ByteArray): Boolean {
        if (handle == 0L || pcmS16Le.isEmpty()) return false
        return NativeAudioBridge.writePlayer(handle, pcmS16Le, 0, pcmS16Le.size) == pcmS16Le.size / 2
    }

    override fun stats(): LiveAudioSinkStats {
        val values = if (handle == 0L) LongArray(6) else NativeAudioBridge.playerStats(handle)
        return LiveAudioSinkStats(
            backend = "AAudio callback",
            underruns = values.getOrElse(0) { 0L },
            droppedInputFrames = values.getOrElse(1) { 0L },
            queuedFrames = values.getOrElse(2) { 0L },
            capacityFrames = values.getOrElse(3) { 0L },
            framesPerBurst = values.getOrElse(4) { 0L },
            sampleRate = values.getOrElse(5) { requestedSampleRate.toLong() }.toInt(),
        )
    }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) NativeAudioBridge.closePlayer(current)
    }

    companion object {
        fun create(sampleRate: Int, channelCount: Int): NativeAaudioSink? {
            val handle = runCatching {
                NativeAudioBridge.createPlayer(sampleRate, channelCount)
            }.getOrDefault(0L)
            return if (handle == 0L) null else NativeAaudioSink(handle, sampleRate)
        }
    }
}

private class AudioTrackLiveSink(
    sampleRate: Int,
    channelCount: Int,
) : LiveAudioSink {
    private val channelConfig = when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        else -> error("unsupported output channel count: $channelCount")
    }
    private val minBufferBytes = AudioTrack.getMinBufferSize(
        sampleRate,
        channelConfig,
        AudioFormat.ENCODING_PCM_16BIT,
    ).also { require(it > 0) { "AudioTrack unsupported" } }
    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .setBufferSizeInBytes(minBufferBytes)
        .build()
    private var droppedInputFrames = 0L

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val oneBurst = (track.bufferCapacityInFrames / 2).coerceAtLeast(1)
                track.setStartThresholdInFrames(oneBurst)
            }
        }
        track.play()
    }

    override fun write(pcmS16Le: ByteArray): Boolean {
        val written = track.write(
            pcmS16Le,
            0,
            pcmS16Le.size,
            AudioTrack.WRITE_NON_BLOCKING,
        )
        val complete = written == pcmS16Le.size
        if (!complete) droppedInputFrames += (pcmS16Le.size - written.coerceAtLeast(0)) / 2L
        return complete
    }

    override fun stats(): LiveAudioSinkStats = LiveAudioSinkStats(
        backend = "AudioTrack fallback",
        underruns = track.underrunCount.toLong(),
        droppedInputFrames = droppedInputFrames,
        queuedFrames = 0,
        capacityFrames = track.bufferCapacityInFrames.toLong(),
        framesPerBurst = 0,
        sampleRate = track.sampleRate,
    )

    override fun close() {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        track.release()
    }
}
