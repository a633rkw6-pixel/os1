package com.vphone.os.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vphone.os.core.VirtualAppInfo
import com.vphone.os.core.VirtualCore
import com.vphone.os.mic.AudioEngineState
import com.vphone.os.mic.VirtualMicService
import com.vphone.os.stub.VirtualMicTestActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VPhoneMainHub()
                }
            }
        }
    }
}

@Composable
private fun VPhoneMainHub() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val micRunning by AudioEngineState.isRunning.collectAsState()
    val connectedCount by AudioEngineState.connectedVirtualApps.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Filled.Phone, contentDescription = "Virtual Phone") },
                    label = { Text("Virtual Phone") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Virtual Mic") },
                    label = {
                        Text(if (micRunning) "ClearVoice ON ($connectedCount)" else "ClearVoice Mic")
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (selectedTab == 0) {
                VirtualPhoneTab(onOpenMicTab = { selectedTab = 1 })
            } else {
                ClearVoiceVirtualMicTab()
            }
        }
    }
}

@Composable
private fun VirtualPhoneTab(onOpenMicTab: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vCore = remember { VirtualCore.get() }

    val micRunning by AudioEngineState.isRunning.collectAsState()
    val connectedApps by AudioEngineState.connectedVirtualApps.collectAsState()

    var virtualApps by remember { mutableStateOf(vCore.packageManager.getInstalledApps()) }
    var isInstalling by remember { mutableStateOf(false) }
    var showCloneDialog by remember { mutableStateOf(false) }

    val apkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isInstalling = true
            scope.launch(Dispatchers.IO) {
                val tmpApk = File(context.cacheDir, "import_${System.currentTimeMillis()}.apk")
                val result = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmpApk.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Cannot read selected file")
                    vCore.packageManager.installFromApkFile(tmpApk).getOrThrow()
                }
                tmpApk.delete()
                withContext(Dispatchers.Main) {
                    isInstalling = false
                    result.onSuccess { app ->
                        virtualApps = vCore.packageManager.getInstalledApps()
                        Toast.makeText(context, "Installed ${app.label} inside VPhoneOS", Toast.LENGTH_SHORT).show()
                    }.onFailure { err ->
                        Toast.makeText(context, "Install failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "VPhoneOS Virtual Phone",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Live Virtual Microphone Status Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (micRunning) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (micRunning) "Virtual Mic: ACTIVE" else "Virtual Mic: STOPPED",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (micRunning) {
                            "Primary mic -> ClearVoice DSP -> $connectedApps virtual app(s)"
                        } else {
                            "Enable ClearVoice Mic to feed voice into virtual apps"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                FilledTonalButton(onClick = onOpenMicTab) {
                    Text(if (micRunning) "Tune" else "Turn On")
                }
            }
        }

        // Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = !isInstalling,
                onClick = {
                    apkPicker.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
                }
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Install APK")
            }

            FilledTonalButton(
                modifier = Modifier.weight(1f),
                enabled = !isInstalling,
                onClick = { showCloneDialog = true }
            ) {
                Text("Clone Host App")
            }
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                context.startActivity(Intent(context, VirtualMicTestActivity::class.java))
            }
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Launch Built-in Virtual Mic Tester (:vp0)")
        }

        if (isInstalling) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Text(
            text = "Installed Virtual Apps (${virtualApps.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        if (virtualApps.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "No virtual apps installed yet.",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Tap 'Install APK' to pick any APK file or 'Clone Host App' to import an app from your real phone. Every app launched here automatically receives microphone input from ClearVoice Live.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(virtualApps, key = { it.packageName }) { app ->
                    VirtualAppCard(
                        app = app,
                        onLaunch = {
                            runCatching { vCore.launchVirtualApp(context, app) }
                                .onFailure {
                                    Toast.makeText(context, "Launch error: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                        },
                        onDelete = {
                            vCore.packageManager.uninstall(app.packageName)
                            virtualApps = vCore.packageManager.getInstalledApps()
                        }
                    )
                }
            }
        }
    }

    if (showCloneDialog) {
        CloneHostAppDialog(
            onDismiss = { showCloneDialog = false },
            onSelectPackage = { pkgName ->
                showCloneDialog = false
                isInstalling = true
                scope.launch(Dispatchers.IO) {
                    val res = vCore.packageManager.cloneInstalledHostApp(pkgName)
                    withContext(Dispatchers.Main) {
                        isInstalling = false
                        res.onSuccess { app ->
                            virtualApps = vCore.packageManager.getInstalledApps()
                            Toast.makeText(context, "Cloned ${app.label} into VPhoneOS", Toast.LENGTH_SHORT).show()
                        }.onFailure { err ->
                            Toast.makeText(context, "Clone failed: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun VirtualAppCard(
    app: VirtualAppInfo,
    onLaunch: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${app.packageName} (v${app.versionName})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = onLaunch) {
                    Text("Open")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Uninstall")
                }
            }
        }
    }
}

@Composable
private fun CloneHostAppDialog(
    onDismiss: () -> Unit,
    onSelectPackage: (String) -> Unit
) {
    val context = LocalContext.current
    val hostApps = remember {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { app ->
                (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                    app.packageName != context.packageName &&
                    pm.getLaunchIntentForPackage(app.packageName) != null
            }
            .map { app -> app.packageName to pm.getApplicationLabel(app).toString() }
            .sortedBy { it.second.lowercase(Locale.US) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clone App into Virtual Phone") },
        text = {
            if (hostApps.isEmpty()) {
                Text("No user-installed apps detected. Use 'Install APK' instead.")
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(hostApps, key = { it.first }) { (pkg, label) ->
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onSelectPackage(pkg) }
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(label, fontWeight = FontWeight.SemiBold)
                                Text(pkg, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun ClearVoiceVirtualMicTab() {
    val context = LocalContext.current
    val isRunning by AudioEngineState.isRunning.collectAsState()
    val inputLevel by AudioEngineState.inputLevel.collectAsState()
    val connectedApps by AudioEngineState.connectedVirtualApps.collectAsState()

    var routeToVPhone by remember { mutableStateOf(AudioEngineState.routeToVirtualPhone) }
    var localMonitor by remember { mutableStateOf(AudioEngineState.localHeadphoneMonitor) }
    var externalUdp by remember { mutableStateOf(AudioEngineState.externalUdpInputEnabled) }

    var noiseGate by remember { mutableFloatStateOf(AudioEngineState.noiseGateAmount) }
    var clarity by remember { mutableFloatStateOf(AudioEngineState.voiceClarity) }
    var warmth by remember { mutableFloatStateOf(AudioEngineState.soothingWarmth) }
    var gain by remember { mutableFloatStateOf(AudioEngineState.micGainBoost) }

    var m2fEnabled by remember { mutableStateOf(AudioEngineState.maleToFemaleEnabled) }
    var pitchSemitones by remember { mutableFloatStateOf(AudioEngineState.pitchSemitones) }
    var feminineTone by remember { mutableFloatStateOf(AudioEngineState.feminineTone) }

    val startService = {
        val intent = Intent(context, VirtualMicService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            startService()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "ClearVoice Virtual Mic",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Real Phone Mic -> ClearVoice DSP -> Virtual Phone Apps ($connectedApps connected)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Live Input Level Meter
            LinearProgressIndicator(
                progress = { inputLevel },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
            )

            // Routing Control Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Feed Voice to Virtual Phone Apps", fontWeight = FontWeight.SemiBold)
                            Text(
                                "All apps inside VPhoneOS use this mic source",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = routeToVPhone,
                            onCheckedChange = {
                                routeToVPhone = it
                                AudioEngineState.routeToVirtualPhone = it
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Local Headphone Monitor", fontWeight = FontWeight.Medium)
                            Text(
                                "Keep OFF unless wearing headphones to avoid echo",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = localMonitor,
                            onCheckedChange = {
                                localMonitor = it
                                AudioEngineState.localHeadphoneMonitor = it
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("External Mic UDP Input (127.0.0.1:48123)", fontWeight = FontWeight.Medium)
                            Text(
                                "Optional: receive 48kHz PCM16 from external host app",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = externalUdp,
                            onCheckedChange = {
                                externalUdp = it
                                AudioEngineState.externalUdpInputEnabled = it
                            }
                        )
                    }
                }
            }

            // Quick Presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        m2fEnabled = false
                        noiseGate = 0.35f; clarity = 0.55f; warmth = 0.70f; gain = 0.45f
                        AudioEngineState.maleToFemaleEnabled = false
                        AudioEngineState.noiseGateAmount = noiseGate
                        AudioEngineState.voiceClarity = clarity
                        AudioEngineState.soothingWarmth = warmth
                        AudioEngineState.micGainBoost = gain
                    }
                ) { Text("Soothing") }

                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        m2fEnabled = false
                        noiseGate = 0.45f; clarity = 0.85f; warmth = 0.30f; gain = 0.55f
                        AudioEngineState.maleToFemaleEnabled = false
                        AudioEngineState.noiseGateAmount = noiseGate
                        AudioEngineState.voiceClarity = clarity
                        AudioEngineState.soothingWarmth = warmth
                        AudioEngineState.micGainBoost = gain
                    }
                ) { Text("Clear") }

                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        m2fEnabled = true
                        pitchSemitones = 5.5f
                        feminineTone = 0.65f
                        noiseGate = 0.40f; clarity = 0.60f; warmth = 0.45f; gain = 0.50f
                        AudioEngineState.maleToFemaleEnabled = true
                        AudioEngineState.pitchSemitones = pitchSemitones
                        AudioEngineState.feminineTone = feminineTone
                        AudioEngineState.noiseGateAmount = noiseGate
                        AudioEngineState.voiceClarity = clarity
                        AudioEngineState.soothingWarmth = warmth
                        AudioEngineState.micGainBoost = gain
                    }
                ) { Text("Female") }
            }

            // Male-to-Female Voice Changer Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Male -> Female Voice Changer",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Real-time pitch lift + vocal formant shaping",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = m2fEnabled,
                            onCheckedChange = {
                                m2fEnabled = it
                                AudioEngineState.maleToFemaleEnabled = it
                            }
                        )
                    }

                    if (m2fEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Pitch Shift", style = MaterialTheme.typography.bodyMedium)
                            val sign = if (pitchSemitones >= 0f) "+" else ""
                            Text(
                                text = String.format(Locale.US, "%s%.1f st", sign, pitchSemitones),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Slider(
                            value = pitchSemitones,
                            valueRange = -4f..9f,
                            onValueChange = {
                                pitchSemitones = it
                                AudioEngineState.pitchSemitones = it
                            }
                        )

                        ControlSlider("Feminine Formant & Softness", feminineTone) {
                            feminineTone = it
                            AudioEngineState.feminineTone = it
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    pitchSemitones = 4.5f; feminineTone = 0.55f
                                    AudioEngineState.pitchSemitones = pitchSemitones
                                    AudioEngineState.feminineTone = feminineTone
                                }
                            ) { Text("Natural") }

                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    pitchSemitones = 6.0f; feminineTone = 0.75f
                                    AudioEngineState.pitchSemitones = pitchSemitones
                                    AudioEngineState.feminineTone = feminineTone
                                }
                            ) { Text("Soft") }

                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    pitchSemitones = 7.5f; feminineTone = 0.85f
                                    AudioEngineState.pitchSemitones = pitchSemitones
                                    AudioEngineState.feminineTone = feminineTone
                                }
                            ) { Text("High") }
                        }
                    }
                }
            }

            // Core Audio Enhancement Sliders
            ControlSlider("Background Noise Reduction", noiseGate) {
                noiseGate = it
                AudioEngineState.noiseGateAmount = it
            }
            ControlSlider("Voice Clarity (2.8 kHz Boost)", clarity) {
                clarity = it
                AudioEngineState.voiceClarity = it
            }
            ControlSlider("Soothing Warmth & De-Harsh", warmth) {
                warmth = it
                AudioEngineState.soothingWarmth = it
            }
            ControlSlider("Mic Volume Boost", gain) {
                gain = it
                AudioEngineState.micGainBoost = it
            }
        }

        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ),
            onClick = {
                if (isRunning) {
                    context.stopService(Intent(context, VirtualMicService::class.java))
                } else {
                    val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        needed.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    val allGranted = needed.all {
                        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                    }
                    if (allGranted) startService() else permissionLauncher.launch(needed.toTypedArray())
                }
            }
        ) {
            Text(
                text = if (isRunning) "Stop Virtual Mic Engine" else "Start ClearVoice Virtual Mic",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun ControlSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(text = "${(value * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value, onValueChange = onValueChange)
    }
}
