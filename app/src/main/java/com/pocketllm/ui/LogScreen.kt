package com.pocketllm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketllm.AppViewModel
import com.pocketllm.server.ServerLog

/**
 * In-app log viewer. Shows the most recent [ServerLog] lines in a monospaced,
 * scrollable list. Use the action row at the top to copy the log to the
 * system clipboard, export it to the public Downloads folder, or force a
 * re-read from disk.
 */
@Composable
fun LogScreen(vm: AppViewModel, onMenu: () -> Unit) {
    val lines by vm.logLines.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // Auto-scroll to the bottom when new lines arrive.
    androidx.compose.runtime.LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Logs", onMenu, subtitle = "${lines.size} lines · tap to copy")

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val text = lines.joinToString("\n")
                    copyToClipboard(context, text)
                    Toast.makeText(context, "Copied ${lines.size} lines to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
            ) { Text("Copy all") }
            OutlinedButton(
                onClick = {
                    val path = vm.exportLogToDownloads()
                    if (path != null) {
                        Toast.makeText(context, "Saved to $path", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Export") }
            OutlinedButton(
                onClick = { vm.refreshLog() },
                modifier = Modifier.weight(1f),
            ) { Text("Refresh") }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .weight(1f),
        ) {
            if (lines.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp)) {
                    Text(
                        "No log lines yet. They will appear here as the app runs, including any crash reports.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                ) {
                    items(lines) { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("PocketLLM log", text))
}
