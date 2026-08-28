package com.pocketllm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("GPU offload")
                        Text(
                            when {
                                !gpuSupported -> "This build has no GPU backend — CPU only"
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
                                ttsEngine == "piper" && !piperReady -> "Piper TTS (downloading model...)"
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
                    } else {
                        Text(
                            "Piper model will be downloaded on first use (~65MB)",
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


private fun currentKeyFor(engine: String, settings: com.pocketllm.settings.AppSettings): String = when (engine) {
    "brave" -> settings.braveKey
    "tavily" -> settings.tavilyKey
    "bing" -> settings.bingKey
    "firecrawl" -> settings.firecrawlKey
    else -> ""
}

private fun labelFor(engine: String): String =
    WebSearch.engines.firstOrNull { it.first == engine }?.second ?: engine
