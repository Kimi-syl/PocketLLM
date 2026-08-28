package com.pocketllm.ui

import android.util.TypedValue
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.size
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.pocketllm.AppViewModel
import com.pocketllm.ChatUiMessage
import com.pocketllm.llm.EngineState
import androidx.compose.material.icons.outlined.VolumeUp
import com.pocketllm.agent.ToolCallCard

private fun formatTime(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

private fun formatStats(message: ChatUiMessage): String? {
    if (message.role != "assistant") return null
    if (message.generatedTokens == 0 && message.tokensPerSecond == null) return null
    val parts = mutableListOf<String>()
    if (message.tokensPerSecond != null && message.tokensPerSecond > 0f) {
        parts.add("%.1f tok/s".format(message.tokensPerSecond))
    }
    if (message.timeToFirstTokenMs != null && message.timeToFirstTokenMs > 0) {
        parts.add("TTFT ${message.timeToFirstTokenMs}ms")
    }
    if (message.generatedTokens > 0) {
        parts.add("${message.generatedTokens} tok")
    }
    if (message.totalDurationMs > 0) {
        val secs = message.totalDurationMs / 1000.0
        parts.add("%.1fs".format(secs))
    }
    return if (parts.isEmpty()) null else parts.joinToString(" \u00B7 ")
}

@Composable
fun ChatScreen(vm: AppViewModel, onMenu: () -> Unit = {}) {
    val messages by vm.chatMessages.collectAsState()
    val generating by vm.generating.collectAsState()
    val engineState by vm.engine.state.collectAsState()
    val webSearchEnabled by vm.webSearchEnabled.collectAsState()
    val agentEnabled by vm.agentEnabled.collectAsState()
    val ttsSpeaking by vm.ttsSpeaking.collectAsState()
    val agentStatus by vm.agentStatus.collectAsState()

    val ready = engineState is EngineState.Ready
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Chat", onMenu)
        if (!ready) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Load a model in the Models tab first",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(messages) { _, message ->
                Bubble(
                    message = message,
                    onSpeak = { vm.speakText(message.content) },
                    ttsSpeaking = ttsSpeaking,
                )
            }
        }

        if (agentEnabled && !generating) {
            Text(
                "\uD83E\uDD16 Agent mode on \u2014 model can use web search, calculator, and datetime",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (webSearchEnabled && !generating) {
            Text(
                "\uD83C\uDF10 Web search on \u2014 next question will include fresh results",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (generating && agentStatus != null) {
            Text(
                agentStatus!!,
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = { vm.toggleWebSearch() }, enabled = ready || !generating) {
                Icon(
                    Icons.Outlined.Public,
                    contentDescription = "Toggle web search",
                    tint = if (webSearchEnabled) MaterialTheme.colorScheme.primary else Color.Unspecified,
                )
            }
            IconButton(onClick = { vm.toggleAgent() }, enabled = ready || !generating) {
                Icon(
                    Icons.Outlined.SmartToy,
                    contentDescription = "Toggle agent mode",
                    tint = if (agentEnabled) MaterialTheme.colorScheme.primary else Color.Unspecified,
                )
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (ready) "Message" else "No model loaded") },
                enabled = ready && !generating,
                maxLines = 4,
            )
            if (generating) {
                Button(onClick = { vm.stopChat() }) { Text("Stop") }
            } else {
                Button(
                    onClick = {
                        vm.sendChat(input)
                        input = ""
                    },
                    enabled = ready && input.isNotBlank(),
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun Bubble(
    message: ChatUiMessage,
    onSpeak: () -> Unit = {},
    ttsSpeaking: Boolean = false,
) {
    val isUser = message.role == "user"
    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Box(
                Modifier
                    .background(
                        color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = if (isUser) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
                        else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (message.content.isBlank()) {
                    Text("…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    MarkdownMessage(message.content)
                }
            }
            for (tool in message.toolCalls) {
                ToolCallCard(tool = tool)
            }
            // Stats line: time + tokens/s + TTFT
            val time = formatTime(message.timestamp)
            val stats = formatStats(message)
            if (time.isNotEmpty() || stats != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    if (stats != null) {
                        Text(
                            text = "· $stats",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    if (!isUser && message.content.isNotBlank() && !message.content.startsWith("…")) {
                        androidx.compose.material3.IconButton(
                            onClick = onSpeak,
                            modifier = Modifier.size(24.dp),
                        ) {
                            androidx.compose.material3.Icon(
                                Icons.Outlined.VolumeUp,
                                contentDescription = "Read aloud",
                                tint = if (ttsSpeaking) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownMessage(content: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val markwon = remember(context) {
        val textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            16f,
            context.resources.displayMetrics,
        )
        Markwon.builder(context)
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create { theme ->
                theme.tableEvenRowBackgroundColor(android.graphics.Color.parseColor("#1A000000"))
                    .tableHeaderRowBackgroundColor(android.graphics.Color.parseColor("#33888888"))
            })
            .usePlugin(
                JLatexMathPlugin.create(textSizePx) { builder ->
                    builder.inlinesEnabled(true)
                },
            )
            .build()
    }
    SelectionContainer {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                android.widget.TextView(ctx).apply {
                    textSize = 16f
                    setTextIsSelectable(true)
                }
            },
            update = { textView ->
                markwon.setMarkdown(textView, content)
                textView.setTextColor(textColor)
            },
        )
    }
}
