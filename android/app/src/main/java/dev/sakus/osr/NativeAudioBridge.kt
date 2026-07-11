// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import java.io.Closeable

internal data class NativePlayerStats(
    val underruns: Long,
    val droppedInputFrames: Long,
    val queuedFrames: Long,
    val capacityFrames: Long,
    val framesPerBurst: Long,
    val sampleRate: Long,
)

internal object NativeAudioBridge {
    val loaded: Boolean = runCatching {
        System.loadLibrary("osr_audio")
        true
    }.getOrDefault(false)

    external fun createPlayer(sampleRate: Int, channelCount: Int): Long
    external fun writePlayer(handle: Long, pcmS16Le: ByteArray, offset: Int, length: Int): Int
    external fun playerStats(handle: Long): LongArray
    external fun closePlayer(handle: Long)

    external fun createOpusEncoder(sampleRate: Int, channelCount: Int, bitrateBps: Int): Long
    external fun setOpusBitrate(handle: Long, bitrateBps: Int): Boolean
    external fun encodeOpus(handle: Long, pcmS16Le: ByteArray, offset: Int, length: Int, frameSamples: Int): ByteArray?
    external fun closeOpusEncoder(handle: Long)

    external fun createOpusDecoder(sampleRate: Int, channelCount: Int): Long
    external fun decodeOpus(handle: Long, packet: ByteArray, offset: Int, length: Int, maxFrameSamples: Int): ByteArray?
    external fun closeOpusDecoder(handle: Long)
}

internal class NativeOpusEncoder private constructor(
    private var handle: Long,
    private val frameSamples: Int,
) : Closeable {
    private var bitrateBps = 0

    fun setBitrateKbps(value: Int): Boolean {
        val requested = value.coerceIn(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS) * 1_000
        if (requested == bitrateBps) return true
        if (!NativeAudioBridge.setOpusBitrate(handle, requested)) return false
        bitrateBps = requested
        return true
    }

    fun encode(pcmS16Le: ByteArray, length: Int): ByteArray? {
        if (handle == 0L || length != frameSamples * 2) return null
        return NativeAudioBridge.encodeOpus(handle, pcmS16Le, 0, length, frameSamples)
    }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) NativeAudioBridge.closeOpusEncoder(current)
    }

    companion object {
        const val MIN_BITRATE_KBPS = 8
        const val MAX_BITRATE_KBPS = 512

        fun create(
            sampleRate: Int,
            channelCount: Int,
            frameSamples: Int,
            bitrateKbps: Int,
        ): NativeOpusEncoder? {
            if (!NativeAudioBridge.loaded) return null
            val bitrate = bitrateKbps.coerceIn(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS) * 1_000
            val handle = runCatching {
                NativeAudioBridge.createOpusEncoder(sampleRate, channelCount, bitrate)
            }.getOrDefault(0L)
            if (handle == 0L) return null
            return NativeOpusEncoder(handle, frameSamples).also { it.bitrateBps = bitrate }
        }
    }
}

internal class NativeOpusDecoder private constructor(
    private var handle: Long,
    private val maxFrameSamples: Int,
) : Closeable {
    fun decode(packet: ByteArray): ByteArray? {
        if (handle == 0L || packet.isEmpty()) return null
        return NativeAudioBridge.decodeOpus(handle, packet, 0, packet.size, maxFrameSamples)
    }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) NativeAudioBridge.closeOpusDecoder(current)
    }

    companion object {
        fun create(sampleRate: Int, channelCount: Int, maxFrameSamples: Int): NativeOpusDecoder? {
            if (!NativeAudioBridge.loaded) return null
            val handle = runCatching {
                NativeAudioBridge.createOpusDecoder(sampleRate, channelCount)
            }.getOrDefault(0L)
            if (handle == 0L) return null
            return NativeOpusDecoder(handle, maxFrameSamples)
        }
    }
}
