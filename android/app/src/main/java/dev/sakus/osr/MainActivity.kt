// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {
    private var sender: PcmAudioSender? = null
    private var receiver: PcmAudioReceiver? = null
    private lateinit var statusView: TextView
    private lateinit var volumeView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRecordAudioPermissionIfNeeded()
        setContentView(createContentView())
    }

    override fun onDestroy() {
        sender?.stop()
        receiver?.stop()
        super.onDestroy()
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
            text = "OpenSoundRelay v0.2 PCM Prototype"
            textSize = 20f
        }
        root.addView(title)

        val targetHost = EditText(this).apply {
            hint = "Target host, e.g. 192.168.1.23"
            setText("127.0.0.1")
            singleLine()
        }
        root.addView(targetHost)

        val targetPort = EditText(this).apply {
            hint = "Target UDP port"
            setText("40124")
            singleLine()
        }
        root.addView(targetPort)

        val bindPort = EditText(this).apply {
            hint = "Receive UDP port"
            setText("40124")
            singleLine()
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

        val startSender = Button(this).apply {
            text = "Start Sender"
        }
        root.addView(startSender)

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
                val gain = progress * 10_000
                volumeView.text = "Parent volume: $progress%"
                sender?.setGainPpm(gain)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        startSender.setOnClickListener {
            requestRecordAudioPermissionIfNeeded()
            val host = targetHost.text.toString().trim()
            val port = targetPort.text.toString().toIntOrNull() ?: 40124
            sender?.stop()
            sender = PcmAudioSender(host, port, ::setStatus).also {
                it.setGainPpm(volume.progress * 10_000)
                it.start()
            }
        }

        stopSender.setOnClickListener {
            sender?.stop()
            sender = null
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

    private fun setStatus(message: String) {
        runOnUiThread {
            statusView.text = message
        }
    }

    private fun requestRecordAudioPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
        }
    }
}
