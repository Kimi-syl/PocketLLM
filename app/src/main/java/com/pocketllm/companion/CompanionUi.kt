package com.pocketllm.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketllm.ui.theme.PocketLLMTheme

/** One line in the companion conversation. */
data class CompanionMsg(val fromUser: Boolean, val text: String)

/**
 * Observable state shared between [CompanionOverlayService] and the composables
 * below. Snapshot state means a plain mutation from the service recomposes the
 * overlay — no extra plumbing needed.
 */
class CompanionUiState {
    var expanded by mutableStateOf(false)
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var input by mutableStateOf("")
    val messages = mutableStateListOf<CompanionMsg>()

    var onSend: ((String) -> Unit)? = null
    var onSummarize: (() -> Unit)? = null
    /** Bubble tapped: open the chat panel. */
    var onExpand: (() -> Unit)? = null
    /** Panel "Hide" tapped: shrink back to the bubble. */
    var onCollapse: (() -> Unit)? = null
    var onDrag: ((Float, Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
}

@Composable
fun CompanionRoot(
    state: CompanionUiState,
    themeMode: String,
) {
    PocketLLMTheme(themeMode = themeMode, dynamicColor = false) {
        if (state.expanded) {
            CompanionPanel(state)
        } else {
            CompanionBubble(state)
        }
    }
}

private const val BUBBLE_GLYPH = "\uD83D\uDC31" // 🐱

@Composable
private fun CompanionBubble(state: CompanionUiState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        state.onDrag?.invoke(drag.x, drag.y)
                    },
                    onDragEnd = { state.onDragEnd?.invoke() },
                    onDragCancel = { state.onDragEnd?.invoke() },
                )
            }
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { state.onExpand?.invoke() })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(BUBBLE_GLYPH, fontSize = 24.sp)
        }
    }
}

@Composable
private fun CompanionPanel(state: CompanionUiState) {
    val listState = rememberLazyListState()

    // Keep the newest message visible while tokens stream in.
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(BUBBLE_GLYPH, fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Companion",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.status.isNotBlank()) {
                        Text(
                            text = state.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                PanelAction("Summary", enabled = !state.busy) { state.onSummarize?.invoke() }
                PanelAction("Hide") { state.onCollapse?.invoke() }
            }

            Spacer(Modifier.height(6.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.messages) { message ->
                    MessageBubble(message)
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    BasicTextField(
                        value = state.input,
                        onValueChange = { state.input = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        decorationBox = { inner ->
                            if (state.input.isEmpty()) {
                                Text(
                                    text = "talk to me…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        },
                    )
                }
                Spacer(Modifier.width(6.dp))
                val canSend = state.input.isNotBlank() && !state.busy
                Surface(
                    shape = CircleShape,
                    color = if (canSend) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(38.dp)
                        .clickable(enabled = canSend) {
                            val text = state.input.trim()
                            state.input = ""
                            state.onSend?.invoke(text)
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (state.busy) "…" else "\u2191", // ↑
                            color = if (canSend) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: CompanionMsg) {
    val alignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    val bg = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = bg,
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Text(
                text = message.text.ifBlank { "…" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun PanelAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .clickable(enabled = enabled) { onClick() },
    )
}
