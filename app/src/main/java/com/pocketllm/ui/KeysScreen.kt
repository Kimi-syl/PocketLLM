package com.pocketllm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.pocketllm.AppViewModel
import com.pocketllm.keys.ApiKeyEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun KeysScreen(vm: AppViewModel) {
    val keys by vm.apiKeys.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf<ApiKeyEntry?>(null) }

    LaunchedEffect(Unit) { vm.refreshKeys() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("API keys", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Use as Bearer tokens with the local server",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = { showCreate = true }) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text("New")
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(keys, key = { it.id }) { entry ->
                KeyRow(
                    entry = entry,
                    onToggle = { vm.setKeyEnabled(entry.id, it) },
                    onDelete = { vm.deleteKey(entry.id) },
                    onReveal = { revealed = entry },
                )
            }
            if (keys.isEmpty()) {
                item {
                    Text(
                        "No keys yet. Create one and paste it into any OpenAI-compatible client.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New API key") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.createKey(name)
                        showCreate = false
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("Cancel") }
            },
        )
    }

    revealed?.let { entry ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { revealed = null },
            title = { Text(entry.name) },
            text = {
                SelectionContainer {
                    Text(entry.key, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(entry.key))
                    revealed = null
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { revealed = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun KeyRow(
    entry: ApiKeyEntry,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onReveal: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${entry.key.take(8)}…${entry.key.takeLast(4)}  ·  ${formatDate(entry.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = entry.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onReveal) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Show key")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete")
            }
        }
    }
}

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMillis))
