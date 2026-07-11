// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.net.InetSocketAddress

class MainActivity : Activity() {
    private var sender: PcmAudioSender? = null
    private var receiver: PcmAudioReceiver? = null
    private lateinit var targets: EditText
    private lateinit var nearby: LinearLayout
    private lateinit var status: TextView
    private lateinit var streamLabel: TextView
    private lateinit var nativeLabel: TextView
    private lateinit var bitrateLabel: TextView
    private lateinit var nativeSync: CheckBox
    private lateinit var quality: Spinner
    private lateinit var bitrate: SeekBar
    private lateinit var bass: SeekBar
    private lateinit var treble: SeekBar
    private lateinit var nativeVolume: NativeVolumeController
    private var streamPercent = 100
    private var bitrateKbps = PcmAudioSender.DEFAULT_BITRATE_KBPS
    private var pendingMic: PendingStart? = null
    private var pendingPlayback: PendingStart? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nativeVolume = NativeVolumeController(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refreshNativeLabel()
        if (AudioRelayService.isActive()) setStatus(AudioRelayService.status())
    }

    override fun onDestroy() {
        sender?.stop()
        receiver?.stop()
        super.onDestroy()
    }

    @Deprecated("Prototype MediaProjection flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        val pending = pendingPlayback.also { pendingPlayback = null }
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
                bitrateKbps = pending.bitrateKbps,
                syncNativeVolume = pending.syncNative,
                projectionResultCode = resultCode,
                projectionData = data,
            )
        }.onSuccess { setStatus("Starting 5 ms Opus relay") }
            .onFailure { setStatus("Failed to start relay: ${it.message}") }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_MIC -> {
                val pending = pendingMic.also { pendingMic = null } ?: return
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startMic(pending)
                } else setStatus("Microphone permission denied")
            }

            REQUEST_PLAYBACK -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    requestProjection()
                } else {
                    pendingPlayback = null
                    setStatus("Record audio permission is required")
                }
            }
        }
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(BG)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        root.addView(text("OpenSoundRelay", 29f, Color.WHITE, true))
        root.addView(text("5 ms Opus · AAudio callback · LAN discovery", 13f, MUTED).apply {
            setPadding(0, 0, 0, dp(12))
        })

        val network = card(root, "Nearby devices", "Find receivers on this Wi-Fi/LAN and add them with one tap.")
        val scanRow = row()
        val scan = action("Scan LAN", true)
        scan.setOnClickListener { scanLan(scan) }
        scanRow.addView(scan, weight())
        scanRow.addView(action("Clear").apply { setOnClickListener { targets.setText("") } }, weight(8))
        network.addView(scanRow)
        nearby = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        network.addView(nearby)
        targets = field("Targets, one per line", "", true)
        network.addView(targets)
        val ports = row()
        val targetPort = field("Target port", "40124").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val receivePort = field("Receive port", "40124").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        ports.addView(targetPort, weight())
        ports.addView(receivePort, weight(8))
        network.addView(ports)

        val volume = card(root, "Volume synchronization", "Stream gain and native media volume stay separate.")
        streamLabel = value("OSR stream gain", "100%")
        volume.addView(streamLabel)
        val streamSeek = styledSeek(200, 100, ACCENT)
        volume.addView(streamSeek)
        val initialNative = nativeVolume.read()
        nativeLabel = value("Native media volume", nativeText(initialNative))
        volume.addView(nativeLabel)
        val nativeSeek = styledSeek(100, initialNative.percent, TEAL)
        volume.addView(nativeSeek)
        nativeSync = CheckBox(this).apply {
            text = "Synchronize sender native volume to receivers"
            isChecked = true
            setTextColor(Color.WHITE)
            buttonTintList = tint(ACCENT_LIGHT)
            setOnCheckedChangeListener { _, enabled ->
                sender?.setNativeVolumeSyncEnabled(enabled)
                AudioRelayService.updateNativeVolumeSync(this@MainActivity, enabled)
            }
        }
        volume.addView(nativeSync)

        val audio = card(
            root,
            "Audio quality & delay",
            "Opus bitrate is continuously adjustable from 8 to 512 kbps. Lower delay may trade stability for freshness.",
        )
        quality = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "Ultra low · 5–30 ms queue",
                    "Balanced · 10–50 ms queue",
                    "Stable · 20–80 ms queue",
                ),
            )
            setSelection(0)
            backgroundTintList = tint(ACCENT_LIGHT)
        }
        audio.addView(quality)
        bitrateLabel = value("Opus bitrate", "$bitrateKbps kbps")
        bitrate = styledSeek(
            NativeOpusEncoder.MAX_BITRATE_KBPS - NativeOpusEncoder.MIN_BITRATE_KBPS,
            bitrateKbps - NativeOpusEncoder.MIN_BITRATE_KBPS,
            TEAL,
        )
        audio.addView(bitrateLabel)
        audio.addView(bitrate)
        audio.addView(text("8 kbps saves bandwidth · 128 kbps is recommended · 512 kbps is maximum quality", 11.5f, MUTED))
        val bassLabel = value("Bass", "0 dB")
        bass = styledSeek(24, 12, ACCENT)
        val trebleLabel = value("Treble", "0 dB")
        treble = styledSeek(24, 12, ACCENT)
        audio.addView(bassLabel)
        audio.addView(bass)
        audio.addView(trebleLabel)
        audio.addView(treble)

        val session = card(root, "Relay session", "Send audio or make this phone discoverable as a receiver.")
        val sendRow = row()
        sendRow.addView(action("Mic sender", true).apply {
            setOnClickListener {
                val pending = pending(targetPort) ?: return@setOnClickListener
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingMic = pending
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                } else startMic(pending)
            }
        }, weight())
        sendRow.addView(action("Device audio", true).apply {
            setOnClickListener {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    setStatus("Device playback capture requires Android 10+")
                    return@setOnClickListener
                }
                pendingPlayback = pending(targetPort) ?: return@setOnClickListener
                requestPlaybackPermissions()
            }
        }, weight(8))
        session.addView(sendRow)
        val receiveRow = row()
        receiveRow.addView(action("Start receiver", true).apply {
            setOnClickListener {
                val port = receivePort.text.toString().toIntOrNull()?.coerceIn(1, 65_535) ?: 40124
                receiver?.stop()
                receiver = PcmAudioReceiver(
                    port,
                    ::setStatus,
                    nativeVolume,
                    nativeSync.isChecked,
                    qualitySettings(),
                    LanDiscovery.defaultDeviceName(),
                ).also { it.start() }
            }
        }, weight())
        receiveRow.addView(action("Stop all", danger = true).apply {
            setOnClickListener {
                sender?.stop()
                sender = null
                receiver?.stop()
                receiver = null
                AudioRelayService.stop(this@MainActivity)
                setStatus("Stopped")
            }
        }, weight(8))
        session.addView(receiveRow)

        status = text("Ready", 14f, TEAL_LIGHT, true).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = background(STATUS_BG, TEAL, 14)
            layoutParams = margins(12)
        }
        root.addView(status)

        streamSeek.listener { progress, _ ->
            streamPercent = progress
            streamLabel.text = "OSR stream gain                                      $progress%"
            sender?.setGainPpm(progress * 10_000)
            AudioRelayService.updateGain(this, progress * 10_000)
        }
        nativeSeek.listener { progress, fromUser ->
            if (fromUser) nativeVolume.apply(NativeVolumeState(progress, false))
            refreshNativeLabel(progress)
        }
        bitrate.listener { progress, _ ->
            bitrateKbps = progress + NativeOpusEncoder.MIN_BITRATE_KBPS
            bitrateLabel.text = "Opus bitrate                                      $bitrateKbps kbps"
            sender?.setBitrateKbps(bitrateKbps)
            AudioRelayService.updateBitrate(this, bitrateKbps)
        }
        bass.listener { progress, _ ->
            bassLabel.text = "Bass                                      ${progress - 12} dB"
        }
        treble.listener { progress, _ ->
            trebleLabel.text = "Treble                                      ${progress - 12} dB"
        }
        return scroll
    }

    private fun scanLan(button: Button) {
        button.isEnabled = false
        button.text = "Scanning…"
        setStatus("Scanning local network")
        LanDiscovery.scan(LanDiscovery.defaultDeviceName()) { result ->
            runOnUiThread {
                button.isEnabled = true
                button.text = "Scan LAN"
                result.onSuccess { devices ->
                    nearby.removeAllViews()
                    if (devices.isEmpty()) nearby.addView(text("No receivers answered.", 13f, MUTED))
                    devices.forEach { device ->
                        nearby.addView(
                            action("＋ ${device.name}   ${device.address.address.hostAddress}:${device.address.port}").apply {
                                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                                setOnClickListener {
                                    addTarget(device.address)
                                    setStatus("Added ${device.name}")
                                }
                            },
                            margins(6),
                        )
                    }
                    setStatus("Found ${devices.size} OSR device(s)")
                }.onFailure { setStatus("LAN scan failed: ${it.message}") }
            }
        }
    }

    private fun addTarget(address: InetSocketAddress) {
        val value = "${address.address?.hostAddress ?: address.hostString}:${address.port}"
        val current = targets.text.toString().split(',', ';', '\n').map { it.trim() }
        if (value !in current) {
            targets.setText(
                targets.text.toString().trim().let { if (it.isEmpty()) value else "$it\n$value" },
            )
        }
    }

    private fun pending(targetPort: EditText): PendingStart? {
        val port = targetPort.text.toString().toIntOrNull() ?: 40124
        val parsed = PcmAudioSender.parseTargets(targets.text.toString(), port)
        if (parsed.isEmpty()) {
            setStatus("No valid targets")
            return null
        }
        return PendingStart(
            targets = parsed,
            gainPpm = streamPercent * 10_000,
            bitrateKbps = bitrateKbps,
            syncNative = nativeSync.isChecked,
        )
    }

    private fun startMic(pending: PendingStart) {
        AudioRelayService.stop(this)
        sender?.stop()
        sender = PcmAudioSender(
            pending.targets,
            PcmAudioSender.CaptureSource.Microphone,
            ::setStatus,
            nativeVolumeProvider = { nativeVolume.read() },
        ).also {
            it.setGainPpm(pending.gainPpm)
            it.setBitrateKbps(pending.bitrateKbps)
            it.setNativeVolumeSyncEnabled(pending.syncNative)
            it.start()
        }
    }

    private fun qualitySettings(): AudioQualitySettings {
        val low = (bass.progress - 12).toFloat()
        val high = (treble.progress - 12).toFloat()
        return when (quality.selectedItemPosition) {
            0 -> AudioQualitySettings.lowLatency(low, high)
            2 -> AudioQualitySettings.stable(low, high)
            else -> AudioQualitySettings.balanced(low, high)
        }
    }

    private fun requestPlaybackPermissions() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isEmpty()) requestProjection() else requestPermissions(missing.toTypedArray(), REQUEST_PLAYBACK)
    }

    private fun requestProjection() {
        if (pendingPlayback == null) return
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    private fun setStatus(message: String) = runOnUiThread { status.text = message }

    private fun refreshNativeLabel(percent: Int? = null) {
        if (!::nativeLabel.isInitialized) return
        val state = nativeVolume.read()
        nativeLabel.text =
            "Native media volume                                      ${percent ?: state.percent}%${if (state.muted) " · muted" else ""}"
    }

    private fun nativeText(state: NativeVolumeState) =
        "${state.percent}%${if (state.muted) " · muted" else ""}"

    private fun card(parent: LinearLayout, title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(16))
            background = background(PANEL, BORDER, 18)
            layoutParams = margins(10)
            addView(text(title, 18f, Color.WHITE, true))
            addView(text(subtitle, 12.5f, MUTED).apply { setPadding(0, dp(2), 0, dp(10)) })
            parent.addView(this)
        }

    private fun field(hintText: String, initial: String, multiline: Boolean = false) =
        EditText(this).apply {
            hint = hintText
            setText(initial)
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = background(INPUT, BORDER, 12)
            if (multiline) {
                minLines = 2
                maxLines = 5
                gravity = Gravity.TOP
            } else {
                setSingleLine(true)
            }
            layoutParams = margins(6)
        }

    private fun action(label: String, primary: Boolean = false, danger: Boolean = false) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            background = background(
                if (danger) DANGER else if (primary) ACCENT else BUTTON,
                if (danger) DANGER_LIGHT else if (primary) ACCENT_LIGHT else BORDER,
                13,
            )
            minHeight = dp(46)
            stateListAnimator = null
        }

    private fun value(name: String, value: String) =
        text("$name                                      $value", 13f, Color.WHITE, true)

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun styledSeek(maximum: Int, current: Int, color: Int) = SeekBar(this).apply {
        max = maximum
        progress = current
        progressTintList = tint(color)
        thumbTintList = tint(ACCENT_LIGHT)
    }

    private fun SeekBar.listener(block: (Int, Boolean) -> Unit) =
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) =
                block(progress, fromUser)

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

    private fun background(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun weight(left: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(left)
        }

    private fun margins(top: Int = 0) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(top)
        }

    private fun tint(color: Int) = android.content.res.ColorStateList.valueOf(color)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class PendingStart(
        val targets: List<InetSocketAddress>,
        val gainPpm: Int,
        val bitrateKbps: Int,
        val syncNative: Boolean,
    )

    companion object {
        private const val REQUEST_MIC = 1001
        private const val REQUEST_PLAYBACK = 1002
        private const val REQUEST_PROJECTION = 2001
        private val BG = Color.rgb(12, 14, 21)
        private val PANEL = Color.rgb(24, 27, 38)
        private val INPUT = Color.rgb(17, 20, 29)
        private val BUTTON = Color.rgb(39, 44, 60)
        private val BORDER = Color.rgb(55, 62, 82)
        private val MUTED = Color.rgb(157, 164, 187)
        private val ACCENT = Color.rgb(103, 92, 255)
        private val ACCENT_LIGHT = Color.rgb(145, 137, 255)
        private val TEAL = Color.rgb(48, 185, 151)
        private val TEAL_LIGHT = Color.rgb(104, 231, 196)
        private val STATUS_BG = Color.rgb(18, 42, 38)
        private val DANGER = Color.rgb(111, 43, 59)
        private val DANGER_LIGHT = Color.rgb(209, 90, 112)
    }
}
