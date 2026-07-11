// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

data class AudioQualitySettings(
    val targetFrames: Int = 2,
    val minFrames: Int = 1,
    val maxFrames: Int = 10,
    val stableFramesBeforeShrink: Int = 200,
    val adaptiveLatency: Boolean = true,
    val bassDb: Float = 0f,
    val trebleDb: Float = 0f,
    val limiter: Boolean = true,
) {
    companion object {
        fun lowLatency(bassDb: Float = 0f, trebleDb: Float = 0f) = AudioQualitySettings(
            targetFrames = 1,
            minFrames = 1,
            maxFrames = 6,
            stableFramesBeforeShrink = 200,
            adaptiveLatency = true,
            bassDb = bassDb,
            trebleDb = trebleDb,
        )

        fun balanced(bassDb: Float = 0f, trebleDb: Float = 0f) = AudioQualitySettings(
            targetFrames = 2,
            minFrames = 1,
            maxFrames = 10,
            stableFramesBeforeShrink = 200,
            adaptiveLatency = true,
            bassDb = bassDb,
            trebleDb = trebleDb,
        )

        fun stable(bassDb: Float = 0f, trebleDb: Float = 0f) = AudioQualitySettings(
            targetFrames = 4,
            minFrames = 2,
            maxFrames = 16,
            stableFramesBeforeShrink = 300,
            adaptiveLatency = true,
            bassDb = bassDb,
            trebleDb = trebleDb,
        )
    }
}

class PcmToneProcessor(
    private val sampleRate: Int,
    private val settings: AudioQualitySettings,
) {
    private var lowPass = 0f
    private val bassGain = 10f.pow(settings.bassDb.coerceIn(-12f, 12f) / 20f)
    private val trebleGain = 10f.pow(settings.trebleDb.coerceIn(-12f, 12f) / 20f)
    private val alpha = (2f * PI.toFloat() * 250f / sampleRate)
        .coerceIn(0.001f, 0.5f)

    fun processPcmS16Le(pcm: ByteArray) {
        var index = 0
        while (index + 1 < pcm.size) {
            val raw = ((pcm[index].toInt() and 0xff) or (pcm[index + 1].toInt() shl 8)).toShort()
            val input = raw.toFloat()
            lowPass += alpha * (input - lowPass)
            val high = input - lowPass
            var output = lowPass * bassGain + high * trebleGain
            if (settings.limiter) {
                output = tanh(output / Short.MAX_VALUE) * Short.MAX_VALUE
            }
            val sample = output
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
                .toInt()
            pcm[index] = (sample and 0xff).toByte()
            pcm[index + 1] = ((sample ushr 8) and 0xff).toByte()
            index += 2
        }
    }
}
