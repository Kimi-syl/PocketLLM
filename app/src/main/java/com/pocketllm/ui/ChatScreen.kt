package com.pocketllm.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import com.pocketllm.AppViewModel
import com.pocketllm.ChatUiMessage
import com.pocketllm.llm.EngineState

@Composable
fun ChatScreen(vm: AppViewModel, onMenu: () -> Unit = {}) {
    val messages by vm.chatMessages.collectAsState()
    val generating by vm.generating.collectAsState()
    val engineState by vm.engine.state.collectAsState()

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
                Bubble(message)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
private fun Bubble(message: ChatUiMessage) {
    val isUser = message.role == "user"
    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .background(
                    color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = if (isUser) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
                    else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(message.content.ifEmpty { "…" }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
