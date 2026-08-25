package com.pocketllm.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.pocketllm.AppViewModel

private enum class Tab(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.AutoMirrored.Outlined.Chat),
    MODELS("Models", Icons.Outlined.Folder),
    SERVER("Server", Icons.Outlined.Public),
    USAGE("Usage", Icons.Outlined.Insights),
    KEYS("Keys", Icons.Outlined.Key),
}

@Composable
fun AppRoot(vm: AppViewModel) {
    var selected by rememberSaveable { mutableStateOf(Tab.CHAT.name) }
    val current = runCatching { Tab.valueOf(selected) }.getOrDefault(Tab.CHAT)

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == current,
                        onClick = { selected = tab.name },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (current) {
                Tab.MODELS -> ModelsScreen(vm)
                Tab.CHAT -> ChatScreen(vm)
                Tab.SERVER -> ServerScreen(vm)
                Tab.USAGE -> UsageScreen(vm)
                Tab.KEYS -> KeysScreen(vm)
            }
        }
    }
}
