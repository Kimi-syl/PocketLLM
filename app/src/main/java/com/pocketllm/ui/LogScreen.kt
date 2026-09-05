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
import androidx.compose.material3.TextButton
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
import java.io.File

/**
 * In-app log viewer. Shows the most recent [ServerLog] lines in a monospaced,
 * scrollable list. Actions: copy to clipboard, export to Downloads, reload
 * from disk, clear, and jump to the latest crash report.
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

    // Load from disk on first composition so we see history from prior runs.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshLog() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Logs", onMenu, subtitle = "${lines.size} lines")

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = {
                    copyToClipboard(context, lines.joinToString("\n"))
                    Toast.makeText(context, "Copied ${lines.size} lines", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
            ) { Text("Copy") }
            OutlinedButton(
                onClick = {
                    val path = vm.exportLogToDownloads()
                    Toast.makeText(
                        context,
                        if (path != null) "Saved: $path" else "Export failed",
                        Toast.LENGTH_LONG,
                    ).show()
                },
                modifier = Modifier.weight(1f),
            ) { Text("Export") }
            OutlinedButton(
                onClick = { vm.refreshLog() },
                modifier = Modifier.weight(1f),
            ) { Text("Reload") }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val reports = listCrashReports(context)
                    if (reports.isEmpty()) {
                        Toast.makeText(context, "No crash reports", Toast.LENGTH_SHORT).show()
                    } else {
                        copyToClipboard(context, reports.first().readText())
                        Toast.makeText(
                            context,
                            "Latest crash copied (${reports.size} total)",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Latest crash") }
            OutlinedButton(
                onClick = {
                    vm.clearLog()
                    Toast.makeText(context, "Log cleared", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
            ) { Text("Clear") }
            OutlinedButton(
                onClick = {
                    val info = runCatching { com.pocketllm.llm.LlamaBridge.backendInfo() }
                        .getOrElse { "GPU info failed: ${it.message}" }
                    com.pocketllm.server.PLog.log(info)
                    Toast.makeText(context, "GPU info appended", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
            ) { Text("GPU info") }
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
                            color = if (line.contains("UNCAUGHT") || line.contains("crash") || line.contains("Error"))
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
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

private fun listCrashReports(context: Context): List<File> {
    val dir = File(context.filesDir, "crash_reports")
    if (!dir.exists()) return emptyList()
    return dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()
}

