package com.pocketllm.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketllm.AppViewModel
import com.pocketllm.sessions.ChatSession
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Tab(val label: String, val icon: ImageVector, val inBottomBar: Boolean) {
    CHAT("Chat", Icons.Outlined.Chat, true),
    MODELS("Models", Icons.Outlined.Folder, true),
    USAGE("Usage", Icons.Outlined.Insights, true),
    SERVER("Server", Icons.Outlined.Public, false),
    KEYS("API keys", Icons.Outlined.Key, false),
    SETTINGS("Settings", Icons.Outlined.Settings, false),
}

@Composable
fun AppRoot(vm: AppViewModel) {
    var selected by rememberSaveable { mutableStateOf(Tab.CHAT.name) }
    val current = runCatching { Tab.valueOf(selected) }.getOrDefault(Tab.CHAT)
    val drawerState = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    LaunchedEffect(Unit) { vm.refreshSessions() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                Text(
                    "PocketLLM",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                // New chat button
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        label = { Text("New chat") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            vm.startNewSession()
                            selected = Tab.CHAT.name
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))

                // Sessions list
                val sessions by vm.sessions.collectAsState()
                val currentId by vm.currentSessionId.collectAsState()
                Text(
                    "Recent chats",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                ) {
                    lazyItems(sessions, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            isCurrent = session.id == currentId,
                            onClick = {
                                scope.launch { drawerState.close() }
                                vm.loadSession(session.id)
                                selected = Tab.CHAT.name
                            },
                            onDelete = { vm.deleteSession(session.id) },
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))

                Tab.entries.forEach { tab ->
                    NavigationDrawerItem(
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                        selected = tab == current,
                        onClick = {
                            scope.launch { drawerState.close() }
                            selected = tab.name
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
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
                    Tab.MODELS -> ModelsScreen(vm, openDrawer)
                    Tab.CHAT -> ChatScreen(vm, openDrawer)
                    Tab.SERVER -> ServerScreen(vm, openDrawer)
                    Tab.USAGE -> UsageScreen(vm, openDrawer)
                    Tab.KEYS -> KeysScreen(vm, openDrawer)
                    Tab.SETTINGS -> SettingsScreen(vm, onOpenTab = { selected = it.name }, onMenu = openDrawer)
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: ChatSession,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = session.preview,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = formatSessionTime(session.updatedAt) + " · ${session.messages.size} msgs",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Delete",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatSessionTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 7 * 86_400_000 -> "${diff / 86_400_000}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts))
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
