// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.InetSocketAddress

class MainActivity : ComponentActivity() {
    private var sender: PcmAudioSender? = null
    private var receiver: PcmAudioReceiver? = null
    private lateinit var nativeVolume: NativeVolumeController
    private lateinit var discovery: LanDiscoveryManager

    private var statusText by mutableStateOf("Ready on the local network")
    private var discoveryStatus by mutableStateOf("Tap refresh to scan")
    private var discoveredDevices by mutableStateOf<List<DiscoveredDevice>>(emptyList())
    private var selectedDevices by mutableStateOf<Map<String, DiscoveredDevice>>(emptyMap())
    private var manualTargets by mutableStateOf("")
    private var receiverPort by mutableStateOf("40124")
    private var qualityProfile by mutableStateOf(AudioQualityProfile.Balanced)
    private var latencyMode by mutableStateOf(LatencyMode.Auto)
    private var nativeVolumePpm by mutableIntStateOf(NativeVolumeController.ONE_MILLION)
    private var senderActive by mutableStateOf(false)
    private var receiverActive by mutableStateOf(false)
    private var receiverStats by mutableStateOf(ReceiverStats())

    private var pendingMicrophoneStart: PendingSenderStart? = null
    private var pendingPlaybackStart: PendingSenderStart? = null
    private var pendingLanAction: (() -> Unit)? = null

    private val microphonePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val pending = pendingMicrophoneStart.also { pendingMicrophoneStart = null } ?: return@registerForActivityResult
        if (grants[Manifest.permission.RECORD_AUDIO] == true || hasPermission(Manifest.permission.RECORD_AUDIO)) {
            startMicrophoneSender(pending)
        } else {
            setStatus("Microphone permission denied")
        }
    }

    private val playbackPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true || hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestPlaybackCaptureConsent()
        } else {
            pendingPlaybackStart = null
            setStatus("Record audio permission is required")
        }
    }

    private val nearbyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingLanAction.also { pendingLanAction = null }?.invoke()
    }

    private val projectionConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pending = pendingPlaybackStart.also { pendingPlaybackStart = null }
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null || pending == null) {
            setStatus("Device playback capture permission denied")
            return@registerForActivityResult
        }

        runCatching {
            sender?.stop()
            sender = null
            AudioRelayService.startPlayback(
                context = this,
                targets = pending.targets,
                gainPpm = pending.deviceVolumePpm,
                projectionResultCode = result.resultCode,
                projectionData = data,
                qualityProfile = pending.qualityProfile,
            )
        }.onSuccess {
            senderActive = true
            setStatus("Device audio relay is running in the foreground")
        }.onFailure { error ->
            senderActive = false
            setStatus("Failed to start relay: ${error.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        restorePreferences()

        nativeVolume = NativeVolumeController(this)
        nativeVolume.start { value ->
            runOnUiThread {
                nativeVolumePpm = value
                sender?.setDeviceVolumePpm(value)
                AudioRelayService.updateDeviceVolume(this, value)
            }
        }
        discovery = LanDiscoveryManager(
            context = this,
            onDevicesChanged = { devices -> runOnUiThread { discoveredDevices = devices } },
            onStatus = { message -> runOnUiThread { discoveryStatus = message } },
        )

        setContent {
            OpenSoundRelayTheme {
                OpenSoundRelayScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        discovery.startDiscovery()
    }

    override fun onResume() {
        super.onResume()
        nativeVolumePpm = nativeVolume.currentPpm()
        if (AudioRelayService.isActive()) {
            senderActive = true
            setStatus(AudioRelayService.status())
        }
    }

    override fun onStop() {
        discovery.stopDiscovery()
        super.onStop()
    }

    override fun onDestroy() {
        sender?.stop()
        receiver?.stop()
        discovery.close()
        nativeVolume.stop()
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun OpenSoundRelayScreen() {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("OpenSoundRelay", fontWeight = FontWeight.Bold)
                            Text(
                                "LAN audio, without the cloud",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { withLanPermission { restartDiscovery() } }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Scan again")
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                HeroCard()
                DiscoveryCard()
                AudioSettingsCard()
                NativeVolumeCard()
                ControlsCard()
                DiagnosticsCard()
                StatusCard()
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun HeroCard() {
        val gradient = Brush.linearGradient(
            listOf(Color(0xFF5146E5), Color(0xFF06A6C8), Color(0xFF18A875)),
        )
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(gradient)
                    .padding(22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Your audio. Every device.",
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Automatic discovery, native volume sync and adaptive latency",
                        color = Color.White.copy(alpha = 0.86f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    @Composable
    private fun DiscoveryCard() {
        SectionCard(
            title = "Nearby receivers",
            subtitle = discoveryStatus,
            icon = Icons.Rounded.Devices,
        ) {
            if (discoveredDevices.isEmpty()) {
                Text(
                    "Start Receiver on another Android device. It will appear here automatically.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                discoveredDevices.forEach { device ->
                    DeviceRow(
                        device = device,
                        selected = selectedDevices.containsKey(device.key),
                        onClick = { toggleDevice(device) },
                    )
                }
            }

            OutlinedTextField(
                value = manualTargets,
                onValueChange = {
                    manualTargets = it
                    savePreferences()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Manual targets (optional)") },
                placeholder = { Text("192.168.1.23:40124") },
                minLines = 2,
            )
            Text(
                "${selectedDevices.size} discovered device(s) selected",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    @Composable
    private fun DeviceRow(
        device: DiscoveredDevice,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        val container = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(container)
                .clickable(onClick = onClick)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color(0xFF19A463) else Color(0xFF7A8292)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.serviceName, fontWeight = FontWeight.SemiBold)
                Text(
                    device.addressLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (selected) "Connected" else "Tap to add",
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AudioSettingsCard() {
        SectionCard(
            title = "Audio engine",
            subtitle = "Choose fidelity and latency behavior",
            icon = Icons.Rounded.Headphones,
        ) {
            Text("Quality", fontWeight = FontWeight.SemiBold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AudioQualityProfile.entries.forEachIndexed { index, profile ->
                    SegmentedButton(
                        selected = qualityProfile == profile,
                        onClick = {
                            qualityProfile = profile
                            savePreferences()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, AudioQualityProfile.entries.size),
                        label = { Text(profile.displayName) },
                    )
                }
            }
            Text(
                qualityProfile.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))
            Text("Latency", fontWeight = FontWeight.SemiBold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LatencyMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = latencyMode == mode,
                        onClick = {
                            latencyMode = mode
                            savePreferences()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, LatencyMode.entries.size),
                        label = { Text(mode.displayName) },
                    )
                }
            }
            Text(
                if (latencyMode == LatencyMode.Auto) {
                    "The buffer grows with network jitter and shrinks again when Wi-Fi stabilizes."
                } else {
                    "${latencyMode.baseLatencyMs} ms baseline with automatic jitter correction."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun NativeVolumeCard() {
        SectionCard(
            title = "Synced device volume",
            subtitle = "Controls Android media volume on parent and receivers",
            icon = Icons.Rounded.VolumeUp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${nativeVolumePpm / 10_000}%",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Hardware volume buttons are synchronized too.",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Slider(
                value = nativeVolumePpm / NativeVolumeController.ONE_MILLION.toFloat(),
                onValueChange = { normalized ->
                    nativeVolume.setPpm((normalized * NativeVolumeController.ONE_MILLION).toInt())
                },
                valueRange = 0f..1f,
            )
        }
    }

    @Composable
    private fun ControlsCard() {
        SectionCard(
            title = "Relay controls",
            subtitle = "Send device audio or become a receiver",
            icon = Icons.Rounded.PlayArrow,
        ) {
            OutlinedTextField(
                value = receiverPort,
                onValueChange = {
                    receiverPort = it.filter(Char::isDigit).take(5)
                    savePreferences()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Receiver UDP port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { requestMicrophoneSender() },
                    enabled = !senderActive,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Microphone")
                }
                Button(
                    onClick = { requestPlaybackSender() },
                    enabled = !senderActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Device audio")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = { withLanPermission { startReceiverNow() } },
                    enabled = !receiverActive,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Headphones, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start receiver")
                }
                OutlinedButton(
                    onClick = { stopAll() },
                    enabled = senderActive || receiverActive,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop")
                }
            }
        }
    }

    @Composable
    private fun DiagnosticsCard() {
        SectionCard(
            title = "Live diagnostics",
            subtitle = receiverStats.format,
            icon = Icons.Rounded.GraphicEq,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile("Jitter", "%.1f ms".format(receiverStats.jitterMs), Modifier.weight(1f))
                MetricTile("Buffer", "${receiverStats.bufferMs} ms", Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile("Lost", receiverStats.lostFrames.toString(), Modifier.weight(1f))
                MetricTile("Late", receiverStats.lateFrames.toString(), Modifier.weight(1f))
            }
            Text(
                "Source: ${receiverStats.source}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(13.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        }
    }

    @Composable
    private fun StatusCard() {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            shape = RoundedCornerShape(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (senderActive || receiverActive) Color(0xFF18A465) else MaterialTheme.colorScheme.outline,
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Text(statusText, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    private fun SectionCard(
        title: String,
        subtitle: String,
        icon: ImageVector,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(9.dp).size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                content()
            }
        }
    }

    private fun requestMicrophoneSender() {
        val pending = buildPendingSender() ?: return
        pendingMicrophoneStart = pending
        val missing = requiredSenderPermissions(includeNotifications = false)
        if (missing.isEmpty()) {
            pendingMicrophoneStart = null
            startMicrophoneSender(pending)
        } else {
            microphonePermissions.launch(missing.toTypedArray())
        }
    }

    private fun requestPlaybackSender() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setStatus("Device playback capture requires Android 10+")
            return
        }
        pendingPlaybackStart = buildPendingSender() ?: return
        val missing = requiredSenderPermissions(includeNotifications = true)
        if (missing.isEmpty()) {
            requestPlaybackCaptureConsent()
        } else {
            playbackPermissions.launch(missing.toTypedArray())
        }
    }

    private fun requestPlaybackCaptureConsent() {
        if (pendingPlaybackStart == null) return
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionConsent.launch(manager.createScreenCaptureIntent())
    }

    private fun startMicrophoneSender(pending: PendingSenderStart) {
        AudioRelayService.stop(this)
        sender?.stop()
        sender = PcmAudioSender(
            targets = pending.targets,
            captureSource = PcmAudioSender.CaptureSource.Microphone,
            qualityProfile = pending.qualityProfile,
            status = ::setStatus,
            onStopped = {
                runOnUiThread {
                    sender = null
                    senderActive = false
                }
            },
        ).also {
            it.setDeviceVolumePpm(pending.deviceVolumePpm)
            it.start()
        }
        senderActive = true
    }

    private fun startReceiverNow() {
        val port = receiverPort.toIntOrNull()?.takeIf { it in 1..65_535 } ?: run {
            setStatus("Enter a valid receiver port")
            return
        }
        receiver?.stop()
        discovery.unregisterReceiver()
        receiverStats = ReceiverStats()
        receiver = PcmAudioReceiver(
            context = this,
            bindPort = port,
            latencyMode = latencyMode,
            status = ::setStatus,
            onStats = { stats -> runOnUiThread { receiverStats = stats } },
            onStopped = {
                runOnUiThread {
                    receiver = null
                    receiverActive = false
                    discovery.unregisterReceiver()
                }
            },
        ).also { it.start() }
        discovery.registerReceiver(port)
        receiverActive = true
        setStatus("Receiver starting on port $port")
    }

    private fun stopAll() {
        sender?.stop()
        sender = null
        receiver?.stop()
        receiver = null
        AudioRelayService.stop(this)
        discovery.unregisterReceiver()
        senderActive = false
        receiverActive = false
        setStatus("Relay stopped")
    }

    private fun buildPendingSender(): PendingSenderStart? {
        val targets = collectTargets()
        if (targets.isEmpty()) {
            setStatus("Select a nearby receiver or enter an address")
            return null
        }
        return PendingSenderStart(
            targets = targets,
            deviceVolumePpm = nativeVolume.currentPpm(),
            qualityProfile = qualityProfile,
        )
    }

    private fun collectTargets(): List<InetSocketAddress> {
        val manual = PcmAudioSender.parseTargets(manualTargets, DEFAULT_PORT)
        return (selectedDevices.values.map { it.address } + manual)
            .distinctBy { "${it.hostString}:${it.port}" }
    }

    private fun toggleDevice(device: DiscoveredDevice) {
        selectedDevices = selectedDevices.toMutableMap().apply {
            if (containsKey(device.key)) remove(device.key) else put(device.key, device)
        }
    }

    private fun restartDiscovery() {
        discovery.stopDiscovery()
        discoveredDevices = emptyList()
        discovery.startDiscovery()
    }

    private fun withLanPermission(action: () -> Unit) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
        ) {
            pendingLanAction = action
            nearbyPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            action()
        }
    }

    private fun requiredSenderPermissions(includeNotifications: Boolean): List<String> {
        return buildList {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
            ) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (
                includeNotifications &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun setStatus(message: String) {
        runOnUiThread { statusText = message }
    }

    private fun restorePreferences() {
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        manualTargets = preferences.getString("manual_targets", "") ?: ""
        receiverPort = preferences.getString("receiver_port", DEFAULT_PORT.toString()) ?: DEFAULT_PORT.toString()
        qualityProfile = AudioQualityProfile.fromId(preferences.getString("quality", null))
        latencyMode = LatencyMode.fromId(preferences.getString("latency", null))
    }

    private fun savePreferences() {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString("manual_targets", manualTargets)
            .putString("receiver_port", receiverPort)
            .putString("quality", qualityProfile.id)
            .putString("latency", latencyMode.id)
            .apply()
    }

    private data class PendingSenderStart(
        val targets: List<InetSocketAddress>,
        val deviceVolumePpm: Int,
        val qualityProfile: AudioQualityProfile,
    )

    companion object {
        private const val DEFAULT_PORT = 40124
        private const val PREFERENCES = "osr_preferences"
    }
}

@Composable
private fun OpenSoundRelayTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(
            primary = Color(0xFFB9C3FF),
            secondary = Color(0xFF67D8C0),
            tertiary = Color(0xFFFFB4A8),
        )
        else -> lightColorScheme(
            primary = Color(0xFF4149B8),
            secondary = Color(0xFF006C5C),
            tertiary = Color(0xFF9C4235),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
