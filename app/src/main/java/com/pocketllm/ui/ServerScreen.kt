package com.pocketllm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketllm.AppViewModel
import com.pocketllm.llm.CpuInfo
import com.pocketllm.server.ServerLog

@Composable
fun ServerScreen(vm: AppViewModel, onMenu: () -> Unit = {}) {
    val running by vm.serverRunning.collectAsState()
    val settings by vm.currentSettings.collectAsState()
    val logs by ServerLog.lines.collectAsState()

    var portText by remember(settings.port) { mutableStateOf(settings.port.toString()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("Server", onMenu) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(
                                    color = if (running) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                )
                        )
                        Text(
                            if (running) "Running on port ${settings.port}" else "Stopped",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (running) {
                            Button(onClick = { vm.stopServer() }) { Text("Stop server") }
                        } else {
                            Button(onClick = { vm.startServer() }) { Text("Start server") }
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Configuration", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                            label = { Text("Port") },
                            singleLine = true,
                            modifier = Modifier.width(140.dp),
                        )
                        OutlinedButton(
                            onClick = { vm.updatePort(portText) },
                            enabled = !running && portText != settings.port.toString(),
                        ) { Text("Apply") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        var ctxText by remember(settings.contextSize) { mutableStateOf(settings.contextSize.toString()) }
                        OutlinedTextField(
                            value = ctxText,
                            onValueChange = { ctxText = it.filter(Char::isDigit).take(5) },
                            label = { Text("Context size") },
                            singleLine = true,
                            modifier = Modifier.width(140.dp),
                        )
                        OutlinedButton(
                            onClick = { vm.updateContextSize(ctxText) },
                            enabled = !running && ctxText != settings.contextSize.toString() && ctxText.isNotBlank(),
                        ) { Text("Apply") }
                    }
                    Text(
                        "Applies to the next model load. Larger contexts need more RAM.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Require API key")
                            Text(
                                "Clients must send Authorization: Bearer <key>",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = settings.requireApiKey, onCheckedChange = { vm.updateRequireApiKey(it) })
                    }
                    var tokenText by remember(settings.hfToken) { mutableStateOf(settings.hfToken) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = tokenText,
                            onValueChange = { tokenText = it },
                            label = { Text("Hugging Face token (optional)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { vm.updateHfToken(tokenText) },
                            enabled = tokenText != settings.hfToken,
                        ) { Text("Save") }
                    }
                    val threads = remember { CpuInfo.recommendedThreads() }
                    Text(
                        "Threads: $threads (big cores)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Endpoints", style = MaterialTheme.typography.titleMedium)
                    val addresses = remember { vm.localAddresses() }
                    if (addresses.isEmpty()) {
                        Text("http://127.0.0.1:${settings.port}/v1", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    } else {
                        addresses.forEach { ip ->
                            Text("http://$ip:${settings.port}/v1", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                    Text(
                        "Point OpenAI-compatible clients at the URL above. On-device apps can use 127.0.0.1.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Text("Request log", style = MaterialTheme.typography.titleMedium)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Box(Modifier.height(220.dp)) {
                    if (logs.isEmpty()) {
                        Text(
                            "No activity yet",
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(contentPadding = PaddingValues(12.dp)) {
                            items(logs.asReversed()) { line ->
                                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
