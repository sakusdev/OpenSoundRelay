// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.net.InetSocketAddress

class MainActivity : Activity() {
    private var sender: PcmAudioSender? = null
    private var receiver: PcmAudioReceiver? = null
    private lateinit var statusView: TextView
    private lateinit var volumeView: TextView
    private var pendingMicrophoneStart: PendingSenderStart? = null
    private var pendingPlaybackStart: PendingSenderStart? = null
    private var currentVolumeProgress: Int = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
    }

    override fun onResume() {
        super.onResume()
        if (AudioRelayService.isActive()) {
            setStatus(AudioRelayService.status())
        }
    }

    override fun onDestroy() {
        sender?.stop()
        receiver?.stop()
        super.onDestroy()
    }

    @Deprecated("MediaProjection consent is kept simple for this prototype.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return

        val pending = pendingPlaybackStart.also { pendingPlaybackStart = null }
        if (resultCode != RESULT_OK || data == null || pending == null) {
            setStatus("Device playback capture permission denied")
            return
        }

        runCatching {
            sender?.stop()
            sender = null
            AudioRelayService.startPlayback(
                context = this,
                targets = pending.targets,
                gainPpm = pending.gainPpm,
                projectionResultCode = resultCode,
                projectionData = data,
            )
        }.onSuccess {
            setStatus("Starting device audio relay in foreground")
        }.onFailure { error ->
            setStatus("Failed to start relay: ${error.message}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_MICROPHONE_PERMISSION -> {
                val pending = pendingMicrophoneStart.also { pendingMicrophoneStart = null } ?: return
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startMicrophoneSender(pending)
                } else {
                    setStatus("Microphone permission denied")
                }
            }

            REQUEST_PLAYBACK_PERMISSIONS -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingPlaybackStart = null
                    setStatus("Record audio permission is required for device playback capture")
                    return
                }
                requestPlaybackCaptureConsent()
            }
        }
    }

    private fun createContentView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val title = TextView(this).apply {
            text = "OpenSoundRelay v0.2 Multi-device PCM Prototype"
            textSize = 20f
        }
        root.addView(title)

        val targetHosts = EditText(this).apply {
            hint = "Targets, one per line or comma-separated. Example: 192.168.1.23:40124"
            setText("127.0.0.1:40124")
            setMinLines(2)
            setMaxLines(4)
            setSingleLine(false)
        }
        root.addView(targetHosts)

        val defaultTargetPort = EditText(this).apply {
            hint = "Default target UDP port when omitted"
            setText("40124")
            setSingleLine(true)
        }
        root.addView(defaultTargetPort)

        val bindPort = EditText(this).apply {
            hint = "Receive UDP port"
            setText("40124")
            setSingleLine(true)
        }
        root.addView(bindPort)

        volumeView = TextView(this).apply {
            text = "Parent volume: 100%"
        }
        root.addView(volumeView)

        val volume = SeekBar(this).apply {
            max = 200
            progress = 100
        }
        root.addView(volume)

        val startMicSender = Button(this).apply {
            text = "Start Mic Sender Fan-out"
        }
        root.addView(startMicSender)

        val startPlaybackSender = Button(this).apply {
            text = "Start Device Audio Sender Fan-out"
        }
        root.addView(startPlaybackSender)

        val stopSender = Button(this).apply {
            text = "Stop Sender"
        }
        root.addView(stopSender)

        val startReceiver = Button(this).apply {
            text = "Start Receiver"
        }
        root.addView(startReceiver)

        val stopReceiver = Button(this).apply {
            text = "Stop Receiver"
        }
        root.addView(stopReceiver)

        statusView = TextView(this).apply {
            text = "Idle"
            textSize = 14f
        }
        root.addView(statusView)

        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentVolumeProgress = progress
                val gain = progress * 10_000
                volumeView.text = "Parent volume: $progress%"
                sender?.setGainPpm(gain)
                AudioRelayService.updateGain(this@MainActivity, gain)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        startMicSender.setOnClickListener {
            val targets = parseTargetsFromUi(targetHosts, defaultTargetPort) ?: return@setOnClickListener
            val pending = PendingSenderStart(targets, currentVolumeProgress * 10_000)
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pendingMicrophoneStart = pending
                requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_MICROPHONE_PERMISSION,
                )
            } else {
                startMicrophoneSender(pending)
            }
        }

        startPlaybackSender.setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                setStatus("Device playback capture requires Android 10+")
                return@setOnClickListener
            }
            val targets = parseTargetsFromUi(targetHosts, defaultTargetPort) ?: return@setOnClickListener
            pendingPlaybackStart = PendingSenderStart(targets, currentVolumeProgress * 10_000)
            requestPlaybackPermissionsOrConsent()
        }

        stopSender.setOnClickListener {
            sender?.stop()
            sender = null
            AudioRelayService.stop(this@MainActivity)
        }

        startReceiver.setOnClickListener {
            val port = bindPort.text.toString().toIntOrNull() ?: 40124
            receiver?.stop()
            receiver = PcmAudioReceiver(port, ::setStatus).also { it.start() }
        }

        stopReceiver.setOnClickListener {
            receiver?.stop()
            receiver = null
        }

        return root
    }

    private fun parseTargetsFromUi(
        targetHosts: EditText,
        defaultTargetPort: EditText,
    ): List<InetSocketAddress>? {
        val defaultPort = defaultTargetPort.text.toString().toIntOrNull() ?: 40124
        val targets = PcmAudioSender.parseTargets(targetHosts.text.toString(), defaultPort)
        if (targets.isEmpty()) {
            setStatus("No valid targets")
            return null
        }
        return targets
    }

    private fun startSender(
        targets: List<InetSocketAddress>,
        captureSource: PcmAudioSender.CaptureSource,
        gainPpm: Int,
    ) {
        sender?.stop()
        sender = PcmAudioSender(
            targets = targets,
            captureSource = captureSource,
            status = ::setStatus,
        ).also {
            it.setGainPpm(gainPpm)
            it.start()
        }
    }

    private fun startMicrophoneSender(pending: PendingSenderStart) {
        AudioRelayService.stop(this)
        startSender(
            targets = pending.targets,
            captureSource = PcmAudioSender.CaptureSource.Microphone,
            gainPpm = pending.gainPpm,
        )
    }

    private fun requestPlaybackPermissionsOrConsent() {
        val missingPermissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), REQUEST_PLAYBACK_PERMISSIONS)
        } else {
            requestPlaybackCaptureConsent()
        }
    }

    private fun requestPlaybackCaptureConsent() {
        if (pendingPlaybackStart == null) return
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION,
        )
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            statusView.text = message
        }
    }

    private data class PendingSenderStart(
        val targets: List<InetSocketAddress>,
        val gainPpm: Int,
    )

    companion object {
        private const val REQUEST_MICROPHONE_PERMISSION = 1001
        private const val REQUEST_PLAYBACK_PERMISSIONS = 1002
        private const val REQUEST_MEDIA_PROJECTION = 2001
    }
}
