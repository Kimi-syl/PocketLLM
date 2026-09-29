package com.pocketllm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ToolDescriptionsScreen(onBack: () -> Unit = {}) {
    var selectedTool by remember { mutableStateOf<ToolItem?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header (matches IMG_5101)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                    )
                }
                Text(
                    "工具描述",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            IconButton(onClick = { /* Reset to defaults */ }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "重設工具描述")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Top card: search_web
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            selectedTool = ToolItem(
                                "search_web",
                                "Search the web for current information, news, and real-time data.",
                                Icons.Outlined.Public,
                            )
                        },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                ) {
                    ToolRow(
                        name = "search_web",
                        desc = "Search the web for current information, news, and real-time data.",
                        icon = Icons.Outlined.Public,
                    )
                }
            }

            // Section: 記憶
            item {
                Text(
                    "記憶",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
                Text(
                    "記憶工具的預設描述會隨記憶提示語言在中/英之間切換。自訂描述依工具名只存一份，切換語言後不會跟著變。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                ) {
                    val memoryTools = listOf(
                        ToolItem("memory_read", "讀取用戶的長期記憶。type 可選：identity（姓名、身邊的人、職業等身份資訊）、workflow（做事方式、工具偏好、習慣）、general（其他通用偏好）。", Icons.Outlined.BookmarkBorder),
                        ToolItem("memory_search_profile", "搜索用戶的長期記憶。當對話中提供的記憶摘要不夠詳細、被截斷（標了 mode=\"summary\"），或需要查找某個特定信息時使用。", Icons.Outlined.BookmarkBorder),
                        ToolItem("memory_update", "寫入一條用戶長期記憶。系統會自動與已有記憶去重合併，不需要先讀取再全文替換。只寫下次新開對話時仍然成立的穩定事實。", Icons.Outlined.BookmarkBorder),
                        ToolItem("memory_edit", "修改一條已有記憶的內容。需要先用 memory_read 或 memory_search_profile 拿到條目 id（形如 mem_xxxxxxxx）後才能修改。", Icons.Outlined.Edit),
                        ToolItem("memory_delete", "歸檔一條記憶（軟刪除）。歸檔後不再出現在記憶摘要和搜索結果裡，用戶仍可以在設置中看到並恢復。需要先用 memory_read 拿到 id。", Icons.Outlined.DeleteOutline),
                        ToolItem("update_user_profile", "更新用戶畫像字段。這些是最穩定的身份信息。不確定的時候不要寫。", Icons.Outlined.PersonOutline),
                        ToolItem("chat_search", "在歷史對話中按關鍵詞搜索消息內容（僅當前助手的話，以及沒有歸屬助手的舊話）。需要回憶之前聊過什麼，或者用戶提到「我們之前說過...」時使用。", Icons.Outlined.Search),
                    )

                    Column {
                        memoryTools.forEachIndexed { index, tool ->
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            Box(Modifier.clickable { selectedTool = tool }) {
                                ToolRow(tool.name, tool.desc, tool.icon)
                            }
                        }
                    }
                }
            }

            // Section: 本機工具
            item {
                Text(
                    "本機工具",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                ) {
                    val localTools = listOf(
                        ToolItem("get_time_info", "Get the current local date and time info from the device. Returns year, month, day, weekday, ISO date and timestamp in timezone.", Icons.Outlined.Schedule),
                        ToolItem("clipboard_tool", "Read or write plain text from the device clipboard. Use action: read or write. For write, provide text. Do NOT write sensitive info.", Icons.Outlined.ContentPaste),
                        ToolItem("text_to_speech", "Speak text aloud to the user using the configured text-to-speech playback. Use this when the user asks you to speak or read aloud.", Icons.Outlined.VolumeUp),
                        ToolItem("ask_user_input_v0", "Ask the user one or more short choice questions when you need clarification, additional information, or a decision before proceeding.", Icons.Outlined.HelpOutline),
                        ToolItem("calculate", "Evaluate a mathematical expression. Supports: + - * / ^ % !, sin() cos() tan() sqrt() ln() abs() floor() ceil() sgn(), constants pi e.", Icons.Outlined.Calculate),
                        ToolItem("calendar_query", "Query calendar events on the user's device within a time range. Specify a custom interval with 'begin'/'end', or quick presets like 'today'.", Icons.Outlined.CalendarMonth),
                        ToolItem("calendar_create", "Create a new calendar event on the user's device. Requires title and start time at minimum. End time defaults to 1 hour after start.", Icons.Outlined.CalendarMonth),
                        ToolItem("get_current_location", "Get the user's current location from the device (one-shot, When In Use). Returns latitude, longitude, accuracy in meters.", Icons.Outlined.LocationOn),
                    )

                    Column {
                        localTools.forEachIndexed { index, tool ->
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            Box(Modifier.clickable { selectedTool = tool }) {
                                ToolRow(tool.name, tool.desc, tool.icon)
                            }
                        }
                    }
                }
            }
        }
    }

    selectedTool?.let { tool ->
        var currentDesc by remember(tool) { mutableStateOf(tool.desc) }
        AlertDialog(
            onDismissRequest = { selectedTool = null },
            title = { Text(tool.name) },
            text = {
                Column {
                    Text(
                        "自訂此工具對模型的提示描述：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = currentDesc,
                        onValueChange = { currentDesc = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedTool = null }) { Text("儲存") }
            },
            dismissButton = {
                TextButton(onClick = { selectedTool = null }) { Text("取消") }
            },
        )
    }
}

private data class ToolItem(
    val name: String,
    val desc: String,
    val icon: ImageVector,
)

@Composable
private fun ToolRow(name: String, desc: String, icon: ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                lineHeight = 16.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
