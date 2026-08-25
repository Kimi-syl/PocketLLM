package com.pocketllm.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketllm.AppViewModel

enum class Tab(val label: String, val icon: ImageVector, val inBottomBar: Boolean) {
    CHAT("Chat", Icons.AutoMirrored.Outlined.Chat, true),
    MODELS("Models", Icons.Outlined.Folder, true),
    SERVER("Server", Icons.Outlined.Public, true),
    USAGE("Usage", Icons.Outlined.Insights, true),
    KEYS("Keys", Icons.Outlined.Key, true),
    SETTINGS("Settings", Icons.Outlined.Settings, false),
}

@Composable
fun AppRoot(vm: AppViewModel) {
    var selected by rememberSaveable { mutableStateOf(Tab.CHAT.name) }
    val current = runCatching { Tab.valueOf(selected) }.getOrDefault(Tab.CHAT)
    val openSettings = { selected = Tab.SETTINGS.name }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.filter { it.inBottomBar }.forEach { tab ->
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
                Tab.MODELS -> ModelsScreen(vm, openSettings)
                Tab.CHAT -> ChatScreen(vm, openSettings)
                Tab.SERVER -> ServerScreen(vm, openSettings)
                Tab.USAGE -> UsageScreen(vm, openSettings)
                Tab.KEYS -> KeysScreen(vm, openSettings)
                Tab.SETTINGS -> SettingsScreen(vm, onOpenTab = { selected = it.name })
            }
        }
    }
}

@Composable
internal fun ScreenHeader(title: String, onMenuClick: () -> Unit, subtitle: String? = null) {
    Row(
        Modifier
            .padding(start = 2.dp, end = 16.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenuClick) {
            Icon(Icons.Outlined.Menu, contentDescription = "Menu")
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
