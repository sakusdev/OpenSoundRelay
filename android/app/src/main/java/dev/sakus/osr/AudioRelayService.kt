// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.net.InetSocketAddress

class AudioRelayService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sender: PcmAudioSender? = null
    private var generation = 0L
    private var gainPpm = 1_000_000
    private var notificationText = "Starting device audio relay"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PLAYBACK -> startPlayback(intent)
            ACTION_SET_GAIN -> setGain(intent.getIntExtra(EXTRA_GAIN_PPM, gainPpm))
            ACTION_STOP -> stopRelay("Sender stopped")
            else -> if (sender == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        generation++
        val activeSender = sender
        sender = null
        activeSender?.stop()
        sessionActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startPlayback(intent: Intent) {
        createNotificationChannel()
        promoteToForeground("Starting device audio relay")

        val targets = readTargets(intent)
        val projectionData = intent.readProjectionData()
        val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED)
        if (targets.isEmpty() || projectionData == null || resultCode != Activity.RESULT_OK) {
            failSession("Invalid playback capture request")
            return
        }

        generation++
        val currentGeneration = generation
        val previousSender = sender
        sender = null
        previousSender?.stop()

        gainPpm = intent.getIntExtra(EXTRA_GAIN_PPM, 1_000_000).coerceIn(0, 2_000_000)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = try {
            projectionManager.getMediaProjection(resultCode, projectionData)
        } catch (error: Throwable) {
            failSession("MediaProjection error: ${error.message ?: error.javaClass.simpleName}")
            return
        }

        if (mediaProjection == null) {
            failSession("Failed to create MediaProjection")
            return
        }

        val nextSender = PcmAudioSender(
            targets = targets,
            captureSource = PcmAudioSender.CaptureSource.Playback(mediaProjection),
            status = { message -> onSenderStatus(currentGeneration, message) },
            onStopped = { onSenderStopped(currentGeneration) },
        )
        sender = nextSender
        nextSender.setGainPpm(gainPpm)
        nextSender.start()
    }

    private fun setGain(value: Int) {
        gainPpm = value.coerceIn(0, 2_000_000)
        sender?.setGainPpm(gainPpm)
    }

    private fun onSenderStatus(senderGeneration: Long, message: String) {
        mainHandler.post {
            if (senderGeneration != generation) return@post
            latestStatus = message
            updateNotification(message)
        }
    }

    private fun onSenderStopped(senderGeneration: Long) {
        mainHandler.post {
            if (senderGeneration != generation) return@post
            sender = null
            sessionActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRelay(statusMessage: String) {
        generation++
        val activeSender = sender
        sender = null
        sessionActive = false
        latestStatus = statusMessage
        activeSender?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failSession(message: String) {
        latestStatus = message
        updateNotification(message)
        stopRelay(message)
    }

    private fun promoteToForeground(message: String) {
        latestStatus = message
        sessionActive = true
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(message: String) {
        if (!sessionActive || message == notificationText) return
        notificationText = message
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        notificationText = message
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AudioRelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_relay_notification)
            .setContentTitle("OpenSoundRelay is sending audio")
            .setContentText(message.take(120))
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(R.drawable.ic_relay_notification, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio relay",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows active device-audio relay sessions"
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun readTargets(intent: Intent): List<InetSocketAddress> {
        val hosts = intent.getStringArrayListExtra(EXTRA_TARGET_HOSTS) ?: return emptyList()
        val ports = intent.getIntegerArrayListExtra(EXTRA_TARGET_PORTS) ?: return emptyList()
        if (hosts.size != ports.size) return emptyList()

        return hosts.indices.mapNotNull { index ->
            val host = hosts[index].trim()
            val port = ports[index]
            if (host.isEmpty() || port !in 1..65_535) null else InetSocketAddress(host, port)
        }
    }

    private fun Intent.readProjectionData(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(EXTRA_PROJECTION_DATA)
        }
    }

    companion object {
        private const val ACTION_START_PLAYBACK = "dev.sakus.osr.action.START_PLAYBACK"
        private const val ACTION_SET_GAIN = "dev.sakus.osr.action.SET_GAIN"
        private const val ACTION_STOP = "dev.sakus.osr.action.STOP"
        private const val EXTRA_TARGET_HOSTS = "target_hosts"
        private const val EXTRA_TARGET_PORTS = "target_ports"
        private const val EXTRA_GAIN_PPM = "gain_ppm"
        private const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        private const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "osr_audio_relay"
        private const val NOTIFICATION_ID = 40124

        @Volatile
        private var sessionActive = false

        @Volatile
        private var latestStatus = "Idle"

        fun startPlayback(
            context: Context,
            targets: List<InetSocketAddress>,
            gainPpm: Int,
            projectionResultCode: Int,
            projectionData: Intent,
        ) {
            val intent = Intent(context, AudioRelayService::class.java).apply {
                action = ACTION_START_PLAYBACK
                putStringArrayListExtra(
                    EXTRA_TARGET_HOSTS,
                    ArrayList(targets.map { it.hostString }),
                )
                putIntegerArrayListExtra(
                    EXTRA_TARGET_PORTS,
                    ArrayList(targets.map { it.port }),
                )
                putExtra(EXTRA_GAIN_PPM, gainPpm.coerceIn(0, 2_000_000))
                putExtra(EXTRA_PROJECTION_RESULT_CODE, projectionResultCode)
                putExtra(EXTRA_PROJECTION_DATA, projectionData)
            }

            sessionActive = true
            latestStatus = "Starting device audio relay"
            try {
                context.startForegroundService(intent)
            } catch (error: Throwable) {
                sessionActive = false
                latestStatus = "Failed to start relay: ${error.message ?: error.javaClass.simpleName}"
                throw error
            }
        }

        fun updateGain(context: Context, gainPpm: Int) {
            if (!sessionActive) return
            val intent = Intent(context, AudioRelayService::class.java).apply {
                action = ACTION_SET_GAIN
                putExtra(EXTRA_GAIN_PPM, gainPpm.coerceIn(0, 2_000_000))
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            if (!sessionActive) return
            context.startService(
                Intent(context, AudioRelayService::class.java).setAction(ACTION_STOP),
            )
        }

        fun isActive(): Boolean = sessionActive

        fun status(): String = latestStatus
    }
}
