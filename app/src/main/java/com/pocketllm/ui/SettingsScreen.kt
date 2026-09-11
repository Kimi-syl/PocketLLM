package com.pocketllm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pocketllm.AppViewModel
import com.pocketllm.util.WebSearch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: AppViewModel, onOpenTab: (Tab) -> Unit = {}, onMenu: () -> Unit = {}) {
    val settings by vm.currentSettings.collectAsState()
    val running by vm.serverRunning.collectAsState()
    val keys by vm.apiKeys.collectAsState()
    val tlsFingerprint by vm.tlsFingerprint.collectAsState()
    val ttsReady by vm.ttsReady.collectAsState()
    val piperReady by vm.piperReady.collectAsState()
    val piperStatus by vm.piperStatus.collectAsState()
    val piperProgress by vm.piperProgress.collectAsState()
    val piperState by vm.piperState.collectAsState()
    val ttsEngine by vm.ttsEngine.collectAsState()
    val exportMessage by vm.exportMessage.collectAsState()

    var _localInstallHint by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("Settings", onMenuClick = onMenu) }

        item {
            SectionCard("Appearance") {
                Text("Theme mode", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (mode, label) ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { vm.updateThemeMode(mode) },
                            label = { Text(label) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Material You colors")
                        Text(
                            if (android.os.Build.VERSION.SDK_INT >= 31) "Tint the app from your wallpaper"
                            else "Requires Android 12+",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.dynamicColor,
                        onCheckedChange = { vm.updateDynamicColor(it) },
                        enabled = android.os.Build.VERSION.SDK_INT >= 31,
                    )
                }
            }
        }

        item {
            CompanionSettingsSection(vm)
        }

        item {
            CompanionPersonalitySection(vm)
        }

        item {
            CompanionAppearanceSection(vm)
        }

        item {
            CompanionProfilesSection(vm)
        }

        item {
            CompanionMemorySection(vm)
        }

        item {
            CompanionCheckInSection(vm)
        }

        item {
            CompanionVoiceSection(vm)
        }

        item {
            CompanionRemindersSection(vm)
        }

        item {
            CompanionMoodSection(vm)
        }

        item {
            CloudEntrySettingsSection(vm)
        }

        item {
            SectionCard("Web search") {
                Text("Engine used when the globe toggle is on in chat", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WebSearch.engines.forEach { (id, label) ->
                        FilterChip(
                            selected = settings.searchEngine == id,
                            onClick = { vm.updateSearchEngine(id) },
                            label = { Text(label) },
                        )
                    }
                }
                if (WebSearch.requiresKey(settings.searchEngine)) {
                    var keyText by remember(settings.searchEngine, settings.hfToken) {
                        mutableStateOf(currentKeyFor(settings.searchEngine, settings))
                    }
                    OutlinedTextField(
                        value = keyText,
                        onValueChange = { keyText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("${labelFor(settings.searchEngine)} API key") },
                        singleLine = true,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = { vm.updateEngineKey(settings.searchEngine, keyText) },
                            enabled = keyText != currentKeyFor(settings.searchEngine, settings),
                        ) { Text("Save key") }
                    }
                }
            }
        }

        item {
            SectionCard("Inference") {
                val loaded by vm.engine.state.collectAsState()
                val gpuSupported = remember { com.pocketllm.llm.LlamaBridge.supportsGpuOffload() }
                val gpuInfo = remember {
                    if (gpuSupported) "" else runCatching { com.pocketllm.llm.LlamaBridge.backendInfo() }.getOrElse { "" }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("GPU offload")
                        Text(
                            when {
                                !gpuSupported -> "No usable GPU device — CPU only (details below)"
                                settings.gpuOffload -> "Layers run on the GPU for faster inference"
                                else -> "CPU only — lower power use, slower generation"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.gpuOffload && gpuSupported,
                        onCheckedChange = { vm.updateGpuOffload(it) },
                        enabled = gpuSupported,
                    )
                }
                Text(
                    "Takes effect the next time a model is loaded. Unload and reload the model to apply.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var turnipOn by remember { mutableStateOf(vm.isTurnipEnabled()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Turnip GPU driver (experimental)")
                        Text(
                            "Open-source Adreno Vulkan driver. May crash on some GPUs; takes effect after app restart.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = turnipOn,
                        onCheckedChange = {
                            turnipOn = it
                            vm.updateTurnipEnabled(it)
                        },
                    )
                }
                if (gpuInfo.isNotBlank()) {
                    Text(
                        gpuInfo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!gpuSupported && loaded is com.pocketllm.llm.EngineState.Ready) {
                    Text(
                        "Loaded model runs on CPU.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loaded is com.pocketllm.llm.EngineState.Ready) {
                    Text(
                        "A model is currently loaded with ${if (settings.gpuOffload) "GPU offload" else "CPU only"}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Speed preset
                Column {
                    Text(
                        "Speed preset",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Together sets context size, batch size, and GPU offload. See the labels below. Takes effect on next model load.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val presets = listOf(
                        com.pocketllm.settings.SpeedPreset.BatterySaver to "Battery",
                        com.pocketllm.settings.SpeedPreset.Balanced to "Balanced",
                        com.pocketllm.settings.SpeedPreset.MaxSpeed to "Max",
                        com.pocketllm.settings.SpeedPreset.Custom to "Custom",
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for ((value, label) in presets) {
                            FilterChip(
                                selected = settings.speedPreset == value,
                                onClick = { vm.updateSpeedPreset(value) },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    val resolved = settings.resolve()
                    val presetHint = when (settings.speedPreset) {
                        com.pocketllm.settings.SpeedPreset.BatterySaver ->
                            "ctx=512 · batch=512 · CPU only (lowest RAM)"
                        com.pocketllm.settings.SpeedPreset.Balanced ->
                            "ctx=2048 · batch=1024 · GPU if available"
                        com.pocketllm.settings.SpeedPreset.MaxSpeed ->
                            "ctx=1024 · batch=2048 · GPU if available"
                        com.pocketllm.settings.SpeedPreset.Custom ->
                            "using your ctx=${resolved.contextSize} · batch=${resolved.batchSize} settings below"
                    }
                    Text(
                        presetHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (settings.speedPreset == com.pocketllm.settings.SpeedPreset.Custom) {
                    // Context window slider (Custom preset only)
                    var contextSize by remember(settings.contextSize) {
                        mutableStateOf(settings.contextSize.toFloat())
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Context window")
                                Text(
                                    "Max tokens the model sees. Larger = more chat history + tool results, but uses more RAM. Takes effect on next model load.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "${contextSize.toInt()}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Slider(
                            value = contextSize,
                            onValueChange = { contextSize = it },
                            valueRange = 512f..32768f,
                            steps = 15,  // 512, 2560, 4608, ..., 32768
                            onValueChangeFinished = {
                                vm.updateContextSize(contextSize.toInt().toString())
                            },
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("512", style = MaterialTheme.typography.labelSmall)
                            Text("8K", style = MaterialTheme.typography.labelSmall)
                            Text("32K", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    // Batch size slider (Custom preset only)
                    var batchSize by remember(settings.batchSize) {
                        mutableStateOf(settings.batchSize.toFloat())
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Batch size")
                                Text(
                                    "Tokens decoded per pass. Larger amortizes GPU launch cost; too large wastes memory on CPU-only.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "${batchSize.toInt()}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Slider(
                            value = batchSize,
                            onValueChange = { batchSize = it },
                            valueRange = 128f..4096f,
                            steps = 15,
                            onValueChangeFinished = {
                                vm.updateBatchSize(batchSize.toInt().toString())
                            },
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("128", style = MaterialTheme.typography.labelSmall)
                            Text("2K", style = MaterialTheme.typography.labelSmall)
                            Text("4096", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                // Max generation tokens slider
                var maxGenTokens by remember(settings.maxGenerationTokens) {
                    mutableStateOf(settings.maxGenerationTokens.toFloat())
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Max generation tokens")
                            Text(
                                "Tokens the model can produce per reply. Higher = longer replies but more memory and time.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "${maxGenTokens.toInt()}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Slider(
                        value = maxGenTokens,
                        onValueChange = { maxGenTokens = it },
                        valueRange = 64f..4096f,
                        steps = 31,  // 64, 192, 320, ... up to 4096
                        onValueChangeFinished = {
                            vm.updateMaxGenerationTokens(maxGenTokens.toInt())
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("64", style = MaterialTheme.typography.labelSmall)
                        Text("2048", style = MaterialTheme.typography.labelSmall)
                        Text("4096", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        item {
            SectionCard("Chat") {
                var promptText by remember(settings.startupPrompt) { mutableStateOf(settings.startupPrompt) }
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Startup system prompt") },
                    placeholder = { Text("e.g. You are a concise assistant.") },
                    minLines = 2,
                    maxLines = 5,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { vm.updateStartupPrompt(promptText) },
                        enabled = promptText != settings.startupPrompt,
                    ) { Text("Save") }
                }
                Text(
                    "Sent as the system message at the start of every chat conversation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Read replies aloud")
                        Text(
                            when {
                                !ttsReady && !piperReady -> "No text-to-speech engine available"
                                ttsEngine == "piper" && piperReady -> "Piper TTS (high quality, offline)"
                                ttsEngine == "piper" && vm.piperInstalled && !piperReady -> "Piper TTS (offline, loading model...)"
                                ttsEngine == "piper" && piperState is com.pocketllm.util.SherpaTtsEngine.State.Downloading -> "Piper TTS (downloading model...)"
                                ttsEngine == "piper" && piperState is com.pocketllm.util.SherpaTtsEngine.State.Extracting -> "Piper TTS (extracting model...)"
                                ttsEngine == "piper" && !piperReady -> "Piper TTS (model not downloaded)"
                                else -> "System TTS engine"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.ttsAutoSpeak,
                        onCheckedChange = { vm.updateTtsAutoSpeak(it) },
                        enabled = ttsReady || piperReady,
                    )
                }
                Text("TTS engine", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = ttsEngine == "system",
                        onClick = { vm.updateTtsEngine("system") },
                        label = { Text("System") },
                    )
                    FilterChip(
                        selected = ttsEngine == "piper",
                        onClick = { vm.updateTtsEngine("piper") },
                        label = { Text("Piper") },
                    )
                }
                if (ttsEngine == "piper" && !piperReady) {
                    val isError = piperState is com.pocketllm.util.SherpaTtsEngine.State.Error
                    if (isError) {
                        Text(
                            "Piper download failed. Check your network connection and tap retry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            piperStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { vm.retryPiperDownload() }) { Text("Retry download") }
                    } else if (piperProgress > 0f && piperProgress < 1f) {
                        Text(
                            "Downloading… ${(piperProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (vm.piperInstalled) {
                        Text(
                            "Piper model is downloaded and loading...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "Piper model not downloaded yet. It downloads automatically (~65MB) the first time Piper is used for speech.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Agent mode")
                        Text(
                            "Lets the model call web search, calculator, and datetime tools while it answers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.agentEnabled,
                        onCheckedChange = { vm.updateAgentEnabled(it) },
                    )
                }
            }
        }

        item {
            SectionCard("Web service") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (running) "Server running" else "Server stopped")
                        Text(
                            "http://127.0.0.1:${settings.port}/v1",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = running,
                        onCheckedChange = { if (it) vm.startServer() else vm.stopServer() },
                    )
                }
                var portText by remember(settings.port) { mutableStateOf(settings.port.toString()) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { vm.updatePort(portText) },
                        enabled = !running && portText != settings.port.toString() && portText.isNotBlank(),
                    ) { Text("Apply") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Require API key")
                        Text(
                            "Bearer auth for all /v1 endpoints",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.requireApiKey, onCheckedChange = { vm.updateRequireApiKey(it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("HTTPS (self-signed TLS)")
                        Text(
                            "Clients must accept the self-signed certificate (e.g. curl -k). Restarts the server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.httpsEnabled, onCheckedChange = { vm.updateHttps(it) })
                }
                if (settings.httpsEnabled && tlsFingerprint != null) {
                    Text(
                        "Cert SHA-256:\n$tlsFingerprint",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Browsers will show a one-time warning until this certificate is trusted on the client device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.exportCertificate() }) { Text("Save .crt to Downloads") }
                        TextButton(
                            onClick = { if (!vm.installCertificateOnDevice()) _localInstallHint = "No certificate yet" }
                        ) { Text("Trust on this device") }
                    }
                    if (_localInstallHint != null) {
                        Text(_localInstallHint!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    exportMessage?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                TextButton(onClick = { onOpenTab(Tab.SERVER) }) { Text("Open Server dashboard →") }
            }
        }

        item {
            SectionCard("API keys") {
                val active = keys.count { it.enabled }
                Text("$active of ${keys.size} keys active")
                Text(
                    "Keys authenticate clients calling your phone's API over the network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onOpenTab(Tab.KEYS) }) { Text("Manage keys →") }
            }
        }

        item {
            SectionCard("About") {
                Text("PocketLLM v$versionName")
                Text(
                    "Local GGUF inference via llama.cpp, served through an OpenAI-compatible API.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { uriHandler.openUri("https://github.com/Kimi-syl/PocketLLM") }) {
                    Text("GitHub repository")
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/**
 * The bubble needs two special permissions that can only be granted in system
 * settings, so this section re-reads them whenever the screen resumes (the user
 * leaves to a system screen and comes back).
 */
@Composable
private fun CompanionSettingsSection(vm: AppViewModel) {
    val settings by vm.currentSettings.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var overlayGranted by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    var accessGranted by remember {
        mutableStateOf(com.pocketllm.companion.CompanionAccessibilityService.isConnected())
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = android.provider.Settings.canDrawOverlays(context)
                accessGranted = com.pocketllm.companion.CompanionAccessibilityService.isConnected()
                // If the user flipped the switch before granting the overlay
                // permission, bring the bubble up now that it can be shown.
                if (settings.companionEnabled) vm.restartCompanionIfEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openOverlaySettings() {
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}"),
                )
            )
        }
    }

    SectionCard("Floating companion") {
        Text(
            "A small bubble that stays on screen after you leave PocketLLM — tap it for company or a quick summary.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show companion bubble")
                Text(
                    if (overlayGranted) "Ready — tap the bubble any time"
                    else "Needs the \"Display over other apps\" permission",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.companionEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && !overlayGranted) openOverlaySettings()
                    else vm.updateCompanionEnabled(enabled)
                },
            )
        }
        if (!overlayGranted) {
            TextButton(onClick = { openOverlaySettings() }) { Text("Grant overlay permission") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Companion screen access")
                Text(
                    if (accessGranted) "On — can read the page when you tap Summary"
                    else "Off — required for page summaries",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = accessGranted,
                onCheckedChange = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        )
                    }
                },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Speak replies out loud")
                Text(
                    "Uses your device text-to-speech voice",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.companionTts,
                onCheckedChange = { vm.updateCompanionTts(it) },
            )
        }

        var persona by remember(settings.companionPersona) { mutableStateOf(settings.companionPersona) }
        OutlinedTextField(
            value = persona,
            onValueChange = { persona = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Extra persona (optional)") },
            minLines = 2,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = { vm.updateCompanionPersona(persona) },
                enabled = persona != settings.companionPersona,
            ) { Text("Save persona") }
        }
    }
}

@Composable
private fun CloudEntrySettingsSection(vm: AppViewModel) {
    val settings by vm.currentSettings.collectAsState()

    SectionCard("Cloud entry") {
        Text(
            "Optional. Used when no local model is loaded, and for longer page summaries. Any OpenAI-compatible endpoint works.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Allow cloud model")
                Text(
                    "Your text leaves the device when this runs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.cloudEnabled, onCheckedChange = { vm.updateCloudEnabled(it) })
        }

        var baseUrl by remember(settings.cloudBaseUrl) { mutableStateOf(settings.cloudBaseUrl) }
        var apiKey by remember(settings.cloudApiKey) { mutableStateOf(settings.cloudApiKey) }
        var model by remember(settings.cloudModel) { mutableStateOf(settings.cloudModel) }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL (ending in /v1)") },
            singleLine = true,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API key") },
            singleLine = true,
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Model name") },
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = {
                    vm.updateCloudBaseUrl(baseUrl)
                    vm.updateCloudApiKey(apiKey)
                    vm.updateCloudModel(model)
                },
                enabled = baseUrl != settings.cloudBaseUrl ||
                    apiKey != settings.cloudApiKey ||
                    model != settings.cloudModel,
            ) { Text("Save") }
        }
    }
}



private fun currentKeyFor(engine: String, settings: com.pocketllm.settings.AppSettings): String = when (engine) {
    "brave" -> settings.braveKey
    "tavily" -> settings.tavilyKey
    "bing" -> settings.bingKey
    "firecrawl" -> settings.firecrawlKey
    else -> ""
}

private fun labelFor(engine: String): String =
    WebSearch.engines.firstOrNull { it.first == engine }?.second ?: engine

/** Who she is, how she sounds, and what she looks like. */
@Composable
private fun CompanionPersonalitySection(vm: AppViewModel) {
    val settings by vm.currentSettings.collectAsState()

    SectionCard("Personality") {
        var name by remember(settings.companionName) { mutableStateOf(settings.companionName) }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Her name") },
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = { vm.updateCompanionName(name) },
                enabled = name != settings.companionName,
            ) { Text("Save name") }
        }

        Text("Style", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Picking a style also sets the sliders below — then adjust to taste.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            com.pocketllm.companion.CompanionPersonas.all.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { preset ->
                        FilterChip(
                            selected = settings.companionStyle == preset.id,
                            onClick = { vm.updateCompanionStyle(preset.id) },
                            label = { Text(preset.label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Text(
            com.pocketllm.companion.CompanionPersonas.byId(settings.companionStyle).blurb,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TraitSlider("Warmth", settings.companionWarmth, "Reserved", "Affectionate") {
            vm.updateCompanionTraits(warmth = it)
        }
        TraitSlider("Directness", settings.companionDirectness, "Gentle", "Blunt") {
            vm.updateCompanionTraits(directness = it)
        }
        TraitSlider("Playfulness", settings.companionPlayfulness, "Sincere", "Playful") {
            vm.updateCompanionTraits(playfulness = it)
        }
        TraitSlider("Talkativeness", settings.companionVerbosity, "Terse", "Chatty") {
            vm.updateCompanionTraits(verbosity = it)
        }

        Text("Bubble", style = MaterialTheme.typography.bodyMedium)
        var glyph by remember(settings.companionGlyph) { mutableStateOf(settings.companionGlyph) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            com.pocketllm.companion.BUBBLE_GLYPH_CHOICES.forEach { face ->
                Surface(
                    shape = CircleShape,
                    color = if (settings.companionGlyph == face) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(36.dp)
                        .clickable {
                            glyph = face
                            // Applied immediately: picking a face is the whole
                            // gesture, so a separate Save step is just a chore.
                            vm.updateCompanionGlyph(face)
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(face, fontSize = 17.sp)
                    }
                }
            }
        }
        OutlinedTextField(
            value = glyph,
            onValueChange = { glyph = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Bubble face (any emoji)") },
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = { vm.updateCompanionGlyph(glyph) },
                enabled = glyph != settings.companionGlyph,
            ) { Text("Save face") }
        }
        TraitSlider("Size", settings.companionBubbleSize, "Small", "Large", range = 36..120) {
            vm.updateCompanionBubbleSize(it)
        }
        TraitSlider("Opacity", settings.companionBubbleAlpha, "Faint", "Solid", range = 20..100) {
            vm.updateCompanionBubbleAlpha(it)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Snap to edge", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Slide her against the nearest side when you let go",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.companionSnapToEdge,
                onCheckedChange = { vm.updateCompanionSnapToEdge(it) },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            // A bubble dragged somewhere awkward — or off the edge on a screen
            // rotation — is otherwise only fixable by dragging it back.
            TextButton(onClick = { vm.resetCompanionBubblePosition() }) {
                Text("Reset position")
            }
        }

        // Renders the real bubble composable, so what is configured here is
        // exactly what appears on screen — styling by sliders alone means
        // guessing at the result.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val previewShape = com.pocketllm.companion.BubbleShape.byId(settings.companionBubbleShape)
            val previewFill = if (settings.companionBubbleColor != 0L) {
                Color(settings.companionBubbleColor)
            } else {
                MaterialTheme.colorScheme.primary
            }
            val previewBorder = if (settings.companionBubbleBorderColor != 0L) {
                Color(settings.companionBubbleBorderColor)
            } else {
                MaterialTheme.colorScheme.surface
            }
            Box(
                modifier = Modifier.size(
                    // The bubble is often larger than a settings row; scaling the
                    // preview down keeps a 120dp bubble from dominating the page
                    // while still showing the shape and proportions honestly.
                    (settings.companionBubbleSize * 1.4f).dp.coerceAtMost(110.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                com.pocketllm.companion.BubbleVisual(
                    sizeDp = settings.companionBubbleSize,
                    shape = previewShape,
                    fill = previewFill,
                    borderWidthDp = settings.companionBubbleBorderWidth,
                    borderColor = previewBorder,
                    glyph = settings.companionGlyph.ifBlank { "🐱" },
                    glyphScale = settings.companionGlyphScale,
                    // Awake, not idle: the fade is a behaviour described by its
                    // own slider, and previewing it faint would misrepresent the
                    // colour the user is choosing.
                    opacity = settings.companionBubbleAlpha / 100f,
                    busy = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Text("Style", style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            com.pocketllm.companion.BUBBLE_PRESETS.forEach { preset ->
                FilterChip(
                    selected = settings.companionBubbleShape == preset.shapeId &&
                        settings.companionBubbleSize == preset.size &&
                        settings.companionBubbleBorderWidth == preset.borderWidth &&
                        settings.companionGlyphScale == preset.glyphScale &&
                        settings.companionBubbleIdleAlpha == preset.idleAlpha,
                    onClick = { vm.applyCompanionBubblePreset(preset) },
                    label = { Text(preset.label) },
                )
            }
        }

        Text("Shape", style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            com.pocketllm.companion.BubbleShape.entries.forEach { shape ->
                val selected = settings.companionBubbleShape == shape.id
                Surface(
                    shape = shape.shapeFor(40),
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { vm.updateCompanionBubbleShape(shape.id) },
                ) {}
            }
        }

        TraitSlider(
            label = "Face size",
            value = settings.companionGlyphScale,
            lowLabel = "Tiny",
            highLabel = "Fills it",
            range = 20..80,
        ) { vm.updateCompanionGlyphScale(it) }

        TraitSlider(
            label = "Outline",
            value = settings.companionBubbleBorderWidth,
            lowLabel = "None",
            highLabel = "Thick",
            range = 0..8,
        ) { vm.updateCompanionBubbleBorderWidth(it) }

        if (settings.companionBubbleBorderWidth > 0) {
            Text("Outline colour", style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 0 is "pick one for me", which is the only sane default when the
                // fill colour is itself theme-driven.
                (BUBBLE_BORDER_COLORS).forEach { (argb, label) ->
                    val selected = settings.companionBubbleBorderColor == argb
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                if (argb == 0L) MaterialTheme.colorScheme.surfaceVariant else Color(argb),
                                CircleShape,
                            )
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            )
                            .clickable { vm.updateCompanionBubbleBorderColor(argb) },
                    ) {}
                    if (argb == 0L) {
                        Text(
                            "Auto",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
            }
        }

        TraitSlider(
            label = "Fade when idle",
            value = settings.companionBubbleIdleAlpha,
            lowLabel = "Faint",
            highLabel = "No fade",
            range = 20..100,
        ) { vm.updateCompanionBubbleIdleAlpha(it) }

        GesturePicker(
            label = "Double-tap",
            current = com.pocketllm.companion.BubbleGesture.byId(settings.companionBubbleDoubleTap),
            onPick = { vm.updateCompanionBubbleDoubleTap(it.id) },
        )
        GesturePicker(
            label = "Long-press",
            current = com.pocketllm.companion.BubbleGesture.byId(settings.companionBubbleLongPress),
            onPick = { vm.updateCompanionBubbleLongPress(it.id) },
        )
        Text(
            "A single tap always opens the chat.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Speech", style = MaterialTheme.typography.bodyMedium)
        // Stored as a multiplier but shown as a percentage, which reads far more
        // naturally than "1.15".
        TraitSlider(
            label = "Rate",
            value = (settings.companionSpeechRate * 100).roundToInt(),
            lowLabel = "Slow",
            highLabel = "Brisk",
            range = 50..200,
        ) { vm.updateCompanionSpeechRate(it / 100f) }
        TraitSlider(
            label = "Pitch",
            value = (settings.companionSpeechPitch * 100).roundToInt(),
            lowLabel = "Low",
            highLabel = "High",
            range = 50..200,
        ) { vm.updateCompanionSpeechPitch(it / 100f) }
        Text(
            "Applies to the system voice. The downloaded Piper voice keeps its own.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Panel", style = MaterialTheme.typography.bodyMedium)
        TraitSlider(
            label = "Text size",
            value = settings.companionPanelFontScale,
            lowLabel = "Small",
            highLabel = "Large",
            range = 80..140,
        ) { vm.updateCompanionPanelFontScale(it) }
    }
}

/**
 * Outline colours for the bubble. 0 means "derive one", which is the only
 * sensible default when the fill may itself be following the theme.
 */
private val BUBBLE_BORDER_COLORS: List<Pair<Long, String>> = listOf(
    0L to "Auto",
    0xFFFFFFFF to "White",
    0xFF000000 to "Black",
    0xFFE91E63 to "Rose",
)

/**
 * A one-tap cycling picker for a gesture action.
 *
 * Cycles rather than opening a dropdown: there are seven options and the list
 * is inside a scrolling settings column, where a popup menu is fiddly and a row
 * of seven chips would not fit.
 */
@Composable
private fun GesturePicker(
    label: String,
    current: com.pocketllm.companion.BubbleGesture,
    onPick: (com.pocketllm.companion.BubbleGesture) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val entries = com.pocketllm.companion.BubbleGesture.entries
                onPick(entries[(entries.indexOf(current) + 1) % entries.size])
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            current.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TraitSlider(
    label: String,
    value: Int,
    lowLabel: String,
    highLabel: String,
    range: IntRange = 0..100,
    onChange: (Int) -> Unit,
) {
    // Held locally while dragging so the handle tracks the finger, and only
    // committed when released: writing settings and refreshing the overlay on
    // every frame would hammer the disk for no visible benefit.
    var local by remember(value) { mutableStateOf(value.toFloat()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(
                "${local.toInt()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(lowLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(highLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Human-readable view of what she has remembered. Editable on purpose: memory
 * that the user cannot see or correct is worse than no memory at all.
 */
@Composable
private fun CompanionMemorySection(vm: AppViewModel) {
    val settings by vm.currentSettings.collectAsState()
    val facts by vm.memoryFacts.collectAsState()

    SectionCard("Memory") {
        Text(
            "Things she has picked up about you, so she doesn't have to be told twice.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remember things")
                Text(
                    if (settings.companionMemoryEnabled) "On" else "Off — nothing is stored",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.companionMemoryEnabled,
                onCheckedChange = { vm.updateCompanionMemoryEnabled(it) },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Learn new facts automatically")
                Text(
                    "Extra short model pass every few messages — slower on local models",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.companionMemoryExtraction,
                onCheckedChange = { vm.updateCompanionMemoryExtraction(it) },
                enabled = settings.companionMemoryEnabled,
            )
        }

        val staleCount by vm.staleMemoryCount.collectAsState()
        val notice by vm.memoryNotice.collectAsState()
        var query by remember { mutableStateOf("") }
        var categoryFilter by remember {
            mutableStateOf<com.pocketllm.companion.MemoryCategory?>(null)
        }

        // Counts are computed here rather than in the ViewModel because they are
        // purely a view of the list it already exposes.
        val counts = facts.groupingBy { it.categoryValue }.eachCount()
        val shown = facts.filter { fact ->
            (categoryFilter == null || fact.categoryValue == categoryFilter) &&
                (query.isBlank() || fact.text.contains(query, ignoreCase = true))
        }

        // Only worth showing a category row for categories that exist.
        if (counts.isNotEmpty()) {
            Text(
                "${facts.size} remembered" + if (staleCount > 0) " · $staleCount may be out of date" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = categoryFilter == null,
                    onClick = { categoryFilter = null },
                    label = { Text("All") },
                )
                counts.entries.sortedByDescending { it.value }.forEach { (category, count) ->
                    FilterChip(
                        selected = categoryFilter == category,
                        onClick = {
                            categoryFilter = if (categoryFilter == category) null else category
                        },
                        label = { Text("${category.label} $count") },
                    )
                }
            }
        }

        if (staleCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$staleCount fact${if (staleCount == 1) "" else "s"} haven't come up in a long time.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.forgetStaleMemory() }) { Text("Forget those") }
            }
        }

        notice?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.dismissMemoryNotice() }) { Text("OK") }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search what she knows") },
            singleLine = true,
        )

        var newFact by remember { mutableStateOf("") }
        OutlinedTextField(
            value = newFact,
            onValueChange = { newFact = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Add something to remember") },
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = {
                    vm.addMemoryFact(newFact)
                    newFact = ""
                },
                enabled = newFact.isNotBlank(),
            ) { Text("Add") }
        }

        if (shown.isEmpty()) {
            Text(
                if (facts.isEmpty()) "Nothing remembered yet." else "Nothing matches that.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (fact in shown) {
                var editing by remember(fact.id) { mutableStateOf(false) }
                var draft by remember(fact.id) { mutableStateOf(fact.text) }

                if (editing) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editing = false }) { Text("Cancel") }
                        TextButton(
                            onClick = {
                                vm.updateMemoryFact(fact.id, draft)
                                editing = false
                            },
                            enabled = draft != fact.text,
                        ) { Text("Save") }
                    }
                } else {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (fact.pinned) {
                                Text("★ ", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                fact.text,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Second line: what kind of fact this is, how sure she is,
                        // and how often it has actually been used. All three are
                        // cheap to show and make the list auditable at a glance.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                buildString {
                                    append(fact.categoryValue.label)
                                    append(" · ")
                                    append("${(fact.confidence * 100).roundToInt()}% sure")
                                    if (fact.reinforcements > 0) append(" · heard ${fact.reinforcements}×")
                                    if (fact.isStale()) append(" · may be out of date")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (fact.isStale()) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            // Tapping cycles the category — same reasoning as the
                            // gesture picker: a dropdown per row is heavy.
                            TextButton(onClick = {
                                val all = com.pocketllm.companion.MemoryCategory.entries
                                val next = all[(all.indexOf(fact.categoryValue) + 1) % all.size]
                                vm.setMemoryFactCategory(fact.id, next)
                            }) { Text("Type") }
                            if (fact.isStale()) {
                                TextButton(onClick = { vm.confirmMemoryFact(fact.id) }) { Text("Still true") }
                            }
                            TextButton(onClick = {
                                vm.setMemoryFactPinned(fact.id, !fact.pinned)
                            }) { Text(if (fact.pinned) "Unpin" else "Pin") }
                            TextButton(onClick = { editing = true }) { Text("Edit") }
                            TextButton(onClick = { vm.removeMemoryFact(fact.id) }) { Text("Forget") }
                        }
                    }
                }
            }
            val clipboard = LocalClipboardManager.current
            var importOpen by remember { mutableStateOf(false) }
            var importText by remember { mutableStateOf("") }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(vm.exportCompanionMemory()))
                }) { Text("Export") }
                TextButton(onClick = { importOpen = !importOpen }) { Text("Import") }
                TextButton(onClick = { vm.clearCompanionMemory() }) { Text("Forget everything") }
            }

            if (importOpen) {
                Text(
                    "Paste an export to merge it in. Existing facts are kept.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Paste export here") },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        vm.importCompanionMemory(importText)
                        importText = ""
                        importOpen = false
                    }, enabled = importText.isNotBlank()) { Text("Merge") }
                }
            }
        }
    }
}

