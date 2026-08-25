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

@Composable
fun SettingsScreen(vm: AppViewModel, onOpenTab: (Tab) -> Unit) {
    val settings by vm.currentSettings.collectAsState()
    val running by vm.serverRunning.collectAsState()
    val keys by vm.apiKeys.collectAsState()
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
        item { ScreenHeader("Settings", onMenuClick = { onOpenTab(Tab.SETTINGS) }) }

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
