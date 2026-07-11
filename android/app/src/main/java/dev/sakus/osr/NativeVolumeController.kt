// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

data class NativeVolumeState(
    val percent: Int,
    val muted: Boolean,
)

class NativeVolumeController(context: Context) {
    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun read(): NativeVolumeState {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percent = (current * 100f / max).roundToInt().coerceIn(0, 100)
        return NativeVolumeState(
            percent = percent,
            muted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC),
        )
    }

    fun apply(state: NativeVolumeState) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val desired = (state.percent.coerceIn(0, 100) * max / 100f)
            .roundToInt()
            .coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, desired, 0)
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (state.muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0,
        )
    }
}
