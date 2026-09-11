package com.pocketllm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pocketllm.AppViewModel
import com.pocketllm.util.WebSearch

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
        OutlinedTextField(
            value = glyph,
            onValueChange = { glyph = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Bubble face (an emoji)") },
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

        if (facts.isEmpty()) {
            Text(
                "Nothing remembered yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (fact in facts) {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (fact.pinned) {
                            Text("★ ", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            fact.text,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            vm.setMemoryFactPinned(fact.id, !fact.pinned)
                        }) { Text(if (fact.pinned) "Unpin" else "Pin") }
                        TextButton(onClick = { editing = true }) { Text("Edit") }
                        TextButton(onClick = { vm.removeMemoryFact(fact.id) }) { Text("Forget") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.clearCompanionMemory() }) { Text("Forget everything") }
            }
        }
    }
}

