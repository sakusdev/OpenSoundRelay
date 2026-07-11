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
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
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

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(18))
            setBackgroundColor(BG)
        }

        root.addView(text("OpenSoundRelay", 30f, TEXT, true).apply {
            letterSpacing = 0.02f
            setPadding(dp(4), 0, 0, dp(18))
        })

        val bodyHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        val connectionBody = scrollBody()
        val audioBody = scrollBody()
        val statusBody = scrollBody()
        bodyHost.addView(connectionBody)
        bodyHost.addView(audioBody)
        bodyHost.addView(statusBody)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = background(SURFACE, BORDER, 22)
            elevation = dp(5).toFloat()
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(18) }
        }

        val tabViews = listOf(
            tab("⌁", "接続"),
            tab("◖))", "音響"),
            tab("▂▅▇", "ステータス"),
        )
        tabViews.forEachIndexed { index, view ->
            tabs.addView(view, LinearLayout.LayoutParams(0, dp(112), 1f).apply {
                if (index > 0) leftMargin = dp(1)
            })
        }

        fun selectTab(selected: Int) {
            connectionBody.visibility = if (selected == 0) View.VISIBLE else View.GONE
            audioBody.visibility = if (selected == 1) View.VISIBLE else View.GONE
            statusBody.visibility = if (selected == 2) View.VISIBLE else View.GONE
            tabViews.forEachIndexed { index, view -> styleTab(view, index == selected) }
        }
        tabViews.forEachIndexed { index, view -> view.setOnClickListener { selectTab(index) } }

        root.addView(tabs)
        root.addView(bodyHost)

        buildConnectionBody(connectionBody.getChildAt(0) as LinearLayout)
        buildAudioBody(audioBody.getChildAt(0) as LinearLayout)
        buildStatusBody(statusBody.getChildAt(0) as LinearLayout)
        selectTab(0)
        return root
    }

    private fun buildConnectionBody(body: LinearLayout) {
        body.addView(sectionTitle("接続", "同じLAN上の端末を探して、送信または受信を開始します。"))

        val network = card(body, "近くの端末", "受信待機中のOpenSoundRelay端末を自動検出します。")
        val scanRow = row()
        val scan = action("LANをスキャン", true)
        scan.setOnClickListener { scanLan(scan) }
        scanRow.addView(scan, weight())
        scanRow.addView(action("クリア").apply { setOnClickListener { targets.setText("") } }, weight(8))
        network.addView(scanRow)
        nearby = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        network.addView(nearby)
        targets = field("接続先（1行に1台）", "", true)
        network.addView(targets)

        val ports = row()
        val targetPort = field("送信先ポート", "40124").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val receivePort = field("受信ポート", "40124").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        ports.addView(targetPort, weight())
        ports.addView(receivePort, weight(8))
        network.addView(ports)

        val session = card(body, "セッション", "マイクまたは端末音声を送るか、この端末を受信機にします。")
        val sendRow = row()
        sendRow.addView(action("マイク送信", true).apply {
            setOnClickListener {
                val pending = pending(targetPort) ?: return@setOnClickListener
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingMic = pending
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                } else startMic(pending)
            }
        }, weight())
        sendRow.addView(action("端末音声", true).apply {
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
        receiveRow.addView(action("受信を開始", true).apply {
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
        receiveRow.addView(action("すべて停止", danger = true).apply {
            setOnClickListener {
                sender?.stop(); sender = null
                receiver?.stop(); receiver = null
                AudioRelayService.stop(this@MainActivity)
                setStatus("Stopped")
            }
        }, weight(8))
        session.addView(receiveRow)
    }

    private fun buildAudioBody(body: LinearLayout) {
        body.addView(sectionTitle("音響", "音質・遅延・音量同期を調整します。設定は送信中にも変更できます。"))

        val audio = card(body, "音質と遅延", "5ms OpusとAAudioを使用します。低遅延ほど回線品質の影響を受けます。")
        quality = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Ultra low · 5–30 ms", "Balanced · 10–50 ms", "Stable · 20–80 ms"),
            )
            setSelection(0)
            backgroundTintList = tint(ACCENT)
        }
        audio.addView(quality)

        bitrateLabel = value("Opus bitrate", "$bitrateKbps kbps")
        bitrate = styledSeek(
            NativeOpusEncoder.MAX_BITRATE_KBPS - NativeOpusEncoder.MIN_BITRATE_KBPS,
            bitrateKbps - NativeOpusEncoder.MIN_BITRATE_KBPS,
            ACCENT,
        )
        audio.addView(bitrateLabel)
        audio.addView(bitrate)
        audio.addView(text("8 kbps — 512 kbps / 推奨 128 kbps", 12f, MUTED))

        val bassLabel = value("Bass", "0 dB")
        bass = styledSeek(24, 12, ACCENT)
        val trebleLabel = value("Treble", "0 dB")
        treble = styledSeek(24, 12, ACCENT)
        audio.addView(bassLabel); audio.addView(bass)
        audio.addView(trebleLabel); audio.addView(treble)

        val volume = card(body, "音量同期", "OSR内の音量と端末のメディア音量は別々に制御されます。")
        streamLabel = value("OSR stream gain", "100%")
        volume.addView(streamLabel)
        val streamSeek = styledSeek(200, 100, ACCENT)
        volume.addView(streamSeek)

        val initialNative = nativeVolume.read()
        nativeLabel = value("Native media volume", nativeText(initialNative))
        volume.addView(nativeLabel)
        val nativeSeek = styledSeek(100, initialNative.percent, ACCENT)
        volume.addView(nativeSeek)
        nativeSync = CheckBox(this).apply {
            text = "送信元の端末音量を受信端末へ同期"
            isChecked = true
            setTextColor(TEXT)
            buttonTintList = tint(ACCENT)
            setPadding(0, dp(8), 0, 0)
            setOnCheckedChangeListener { _, enabled ->
                sender?.setNativeVolumeSyncEnabled(enabled)
                AudioRelayService.updateNativeVolumeSync(this@MainActivity, enabled)
            }
        }
        volume.addView(nativeSync)

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
        bass.listener { progress, _ -> bassLabel.text = "Bass                                      ${progress - 12} dB" }
        treble.listener { progress, _ -> trebleLabel.text = "Treble                                      ${progress - 12} dB" }
    }

    private fun buildStatusBody(body: LinearLayout) {
        body.addView(sectionTitle("ステータス", "現在の接続状態と低遅延オーディオの診断値を表示します。"))
        val live = card(body, "ライブ状態", "queue / hwq / xruns / stale / sink-drop をここで確認できます。")
        status = text("Ready", 15f, TEXT, true).apply {
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = background(ACCENT_SOFT, ACCENT, 16)
            layoutParams = margins(8)
        }
        live.addView(status)

        val hints = card(body, "見方", "遅延原因を切り分けるための目安です。")
        hints.addView(metric("queue", "ネットワーク側の待機時間。Ultra lowでは5〜15msが理想。"))
        hints.addView(metric("hwq", "AAudioのネイティブ出力リングに残っている音声。"))
        hints.addView(metric("xruns", "端末の音声コールバックが間に合わなかった回数。"))
        hints.addView(metric("stale", "遅延を増やさないため破棄した古いフレーム。"))
        hints.addView(metric("sink-drop", "出力が追いつかずネイティブ層で破棄したサンプル。"))
    }

    private fun scanLan(button: Button) {
        button.isEnabled = false
        button.text = "スキャン中…"
        setStatus("Scanning local network")
        LanDiscovery.scan(LanDiscovery.defaultDeviceName()) { result ->
            runOnUiThread {
                button.isEnabled = true
                button.text = "LANをスキャン"
                result.onSuccess { devices ->
                    nearby.removeAllViews()
                    if (devices.isEmpty()) nearby.addView(text("受信端末が見つかりませんでした。", 13f, MUTED))
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
            targets.setText(targets.text.toString().trim().let { if (it.isEmpty()) value else "$it\n$value" })
        }
    }

    private fun pending(targetPort: EditText): PendingStart? {
        val port = targetPort.text.toString().toIntOrNull() ?: 40124
        val parsed = PcmAudioSender.parseTargets(targets.text.toString(), port)
        if (parsed.isEmpty()) {
            setStatus("No valid targets")
            return null
        }
        return PendingStart(parsed, streamPercent * 10_000, bitrateKbps, nativeSync.isChecked)
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

    private fun setStatus(message: String) = runOnUiThread {
        if (::status.isInitialized) status.text = message
    }

    private fun refreshNativeLabel(percent: Int? = null) {
        if (!::nativeLabel.isInitialized) return
        val state = nativeVolume.read()
        nativeLabel.text = "Native media volume                                      ${percent ?: state.percent}%${if (state.muted) " · muted" else ""}"
    }

    private fun nativeText(state: NativeVolumeState) = "${state.percent}%${if (state.muted) " · muted" else ""}"

    private fun scrollBody(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), 0, dp(2), dp(28))
        }
        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
    }

    private fun tab(icon: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        addView(text(icon, 24f, TEXT, true).apply { gravity = Gravity.CENTER })
        addView(text(label, 16f, TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun styleTab(tab: LinearLayout, selected: Boolean) {
        tab.background = background(if (selected) ACCENT_SOFT else Color.TRANSPARENT, Color.TRANSPARENT, 20)
        for (index in 0 until tab.childCount) {
            (tab.getChildAt(index) as? TextView)?.setTextColor(if (selected) ACCENT else TEXT)
        }
    }

    private fun sectionTitle(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(2), dp(4), dp(10))
        addView(text(title, 27f, TEXT, true))
        addView(text(subtitle, 13f, MUTED).apply { setPadding(0, dp(4), 0, 0) })
    }

    private fun card(parent: LinearLayout, title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(18))
            background = background(SURFACE, BORDER, 20)
            elevation = dp(2).toFloat()
            layoutParams = margins(12)
            addView(text(title, 18f, TEXT, true))
            addView(text(subtitle, 12.5f, MUTED).apply { setPadding(0, dp(3), 0, dp(12)) })
            parent.addView(this)
        }

    private fun metric(name: String, description: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(9), 0, dp(9))
        addView(text(name, 14f, ACCENT, true))
        addView(text(description, 12.5f, MUTED))
    }

    private fun field(hintText: String, initial: String, multiline: Boolean = false) = EditText(this).apply {
        hint = hintText
        setText(initial)
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        textSize = 14f
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = background(INPUT, BORDER, 14)
        if (multiline) {
            minLines = 2; maxLines = 5; gravity = Gravity.TOP
        } else setSingleLine(true)
        layoutParams = margins(7)
    }

    private fun action(label: String, primary: Boolean = false, danger: Boolean = false) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(if (primary || danger) Color.WHITE else TEXT)
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        background = background(
            if (danger) DANGER else if (primary) ACCENT else BUTTON,
            if (danger) DANGER else if (primary) ACCENT else BORDER,
            14,
        )
        minHeight = dp(48)
        stateListAnimator = null
    }

    private fun value(name: String, value: String) = text("$name                                      $value", 13f, TEXT, true)

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
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
        thumbTintList = tint(color)
    }

    private fun SeekBar.listener(block: (Int, Boolean) -> Unit) =
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = block(progress, fromUser)
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

    private fun background(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun weight(left: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        leftMargin = dp(left)
    }

    private fun margins(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

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
        private val BG = Color.rgb(247, 248, 251)
        private val SURFACE = Color.WHITE
        private val INPUT = Color.rgb(249, 250, 252)
        private val BUTTON = Color.rgb(244, 246, 250)
        private val BORDER = Color.rgb(226, 230, 238)
        private val TEXT = Color.rgb(26, 38, 58)
        private val MUTED = Color.rgb(112, 123, 142)
        private val ACCENT = Color.rgb(40, 112, 238)
        private val ACCENT_SOFT = Color.rgb(233, 241, 255)
        private val DANGER = Color.rgb(207, 70, 86)
    }
}
