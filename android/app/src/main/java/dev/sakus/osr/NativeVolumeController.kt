// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

class NativeVolumeController(context: Context) {
    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private var listener: ((Int) -> Unit)? = null
    private var lastReportedPpm = -1

    fun start(listener: (Int) -> Unit) {
        this.listener = listener
        if (observer == null) {
            observer = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    reportIfChanged()
                }

                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    reportIfChanged()
                }
            }.also {
                applicationContext.contentResolver.registerContentObserver(
                    Settings.System.CONTENT_URI,
                    true,
                    it,
                )
            }
        }
        reportIfChanged(force = true)
    }

    fun stop() {
        observer?.let { applicationContext.contentResolver.unregisterContentObserver(it) }
        observer = null
        listener = null
        lastReportedPpm = -1
    }

    fun currentPpm(): Int {
        val min = minVolume()
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(min + 1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(min, max)
        return (((current - min).toLong() * ONE_MILLION) / (max - min)).toInt()
    }

    fun currentPercent(): Int = (currentPpm() / 10_000.0).toInt().coerceIn(0, 100)

    fun setPpm(value: Int, showSystemUi: Boolean = false) {
        val normalized = value.coerceIn(0, ONE_MILLION)
        val min = minVolume()
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(min + 1)
        val step = min + ((normalized.toLong() * (max - min) + ONE_MILLION / 2) / ONE_MILLION).toInt()
        val flags = if (showSystemUi) AudioManager.FLAG_SHOW_UI else 0
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != step) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, flags)
        }
        reportIfChanged(force = true)
    }

    private fun minVolume(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else {
            0
        }
    }

    private fun reportIfChanged(force: Boolean = false) {
        val value = currentPpm()
        if (!force && value == lastReportedPpm) return
        lastReportedPpm = value
        listener?.invoke(value)
    }

    companion object {
        const val ONE_MILLION = 1_000_000
    }
}
