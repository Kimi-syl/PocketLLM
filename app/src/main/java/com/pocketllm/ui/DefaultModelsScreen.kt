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
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketllm.AppViewModel

@Composable
fun DefaultModelsScreen(
    vm: AppViewModel,
    onBack: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header (Matches IMG_5101)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                )
            }
            Column {
                Text(
                    "預設模型與提示詞",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "配置各環節的預設模型與推理提示詞範本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DefaultModelsContent(vm = vm)
    }
}

@Composable
fun DefaultModelsContent(vm: AppViewModel) {
    val settings by vm.currentSettings.collectAsState()
    val localModels by vm.models.collectAsState()

    var pickerTarget by remember { mutableStateOf<String?>(null) }
    var configTarget by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. 聊天模型
        item {
            ModelRoleCard(
                title = "聊天模型",
                subtitle = "全域預設的聊天模型",
                currentModel = settings.chatModel,
                onSelectModel = { pickerTarget = "chat" },
                onConfigure = { configTarget = "chat" },
            )
        }

        // 2. 每個對話獨立模型
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "每個對話獨立模型",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Switch(
                            checked = settings.perChatModelIndependent,
                            onCheckedChange = { vm.updatePerChatModelIndependent(it) },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "開啟後，在對話中切換模型只影響目前對話；關閉後會直接修改目前助手的模型，使用該助手的所有對話都會跟隨。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        // 3. 標題總結模型
        item {
            ModelRoleCard(
                title = "標題總結模型",
                subtitle = "用於總結對話標題，預設跟隨目前對話模型，也可指定其他模型。",
                currentModel = if (settings.titleSummaryEnabled) settings.titleSummaryModel else "disabled",
                promptSnippet = settings.titleSummaryPrompt,
                onSelectModel = { pickerTarget = "titleSummary" },
                onToggleDisabled = {
                    vm.updateTitleSummaryEnabled(!settings.titleSummaryEnabled)
                },
                isDisabled = !settings.titleSummaryEnabled,
                showDisableToggle = true,
                onConfigure = { configTarget = "titleSummary" },
            )
        }

        // 4. 摘要模型
        item {
            ModelRoleCard(
                title = "摘要模型",
                subtitle = "用於生成對話摘要的模型，推薦使用快速且便宜的模型",
                currentModel = settings.summaryModel,
                promptSnippet = settings.summaryPrompt,
                onSelectModel = { pickerTarget = "summary" },
                onConfigure = { configTarget = "summary" },
            )
        }

        // 5. 聊天建議模型
        item {
            ModelRoleCard(
                title = "聊天建議模型",
                subtitle = "用於在助手回覆後生成聊天建議，可跟隨目前對話模型或指定其他模型。預設未啟用。",
                currentModel = if (settings.chatSuggestionEnabled) settings.chatSuggestionModel else "disabled",
                promptSnippet = settings.chatSuggestionPrompt,
                onSelectModel = { pickerTarget = "suggestion" },
                onRefresh = {
                    vm.updateChatSuggestionEnabled(!settings.chatSuggestionEnabled)
                },
                onToggleDisabled = {
                    vm.updateChatSuggestionEnabled(!settings.chatSuggestionEnabled)
                },
                isDisabled = !settings.chatSuggestionEnabled,
                showDisableToggle = true,
                showRefresh = true,
                onConfigure = { configTarget = "suggestion" },
            )
        }

        // 6. 壓縮模型
        item {
            ModelRoleCard(
                title = "壓縮模型",
                subtitle = "用於壓縮對話上下文的模型，建議使用快速模型",
                currentModel = settings.compressionModel,
                promptSnippet = settings.compressionPrompt,
                onSelectModel = { pickerTarget = "compression" },
                onConfigure = { configTarget = "compression" },
            )
        }

        // 7. 翻譯模型
        item {
            ModelRoleCard(
                title = "翻譯模型",
                subtitle = "用於翻譯訊息內容的模型，推薦使用快速且準確的模型",
                currentModel = settings.translationModel,
                promptSnippet = settings.translationPrompt,
                onSelectModel = { pickerTarget = "translation" },
                onConfigure = { configTarget = "translation" },
            )
        }

        // 8. OCR 模型
        item {
            ModelRoleCard(
                title = "OCR 模型",
                subtitle = "用於對圖片執行文字辨識的模型",
                currentModel = settings.ocrModel,
                promptSnippet = settings.ocrPrompt,
                onSelectModel = { pickerTarget = "ocr" },
                onConfigure = { configTarget = "ocr" },
            )
        }
    }

    // Model Picker Dialog (Optimized with Local, Cloud, and Custom Model input)
    pickerTarget?.let { targetKey ->
        val currentVal = when (targetKey) {
            "chat" -> settings.chatModel
            "titleSummary" -> settings.titleSummaryModel
            "summary" -> settings.summaryModel
            "suggestion" -> settings.chatSuggestionModel
            "compression" -> settings.compressionModel
            "translation" -> settings.translationModel
            "ocr" -> settings.ocrModel
            else -> "use_current"
        }

        val onSelect: (String) -> Unit = { selectedModel ->
            when (targetKey) {
                "chat" -> vm.updateDefaultChatModel(selectedModel)
                "titleSummary" -> {
                    vm.updateTitleSummaryModel(selectedModel)
                    vm.updateTitleSummaryEnabled(true)
                }
                "summary" -> vm.updateSummaryModel(selectedModel)
                "suggestion" -> {
                    vm.updateChatSuggestionModel(selectedModel)
                    vm.updateChatSuggestionEnabled(true)
                }
                "compression" -> vm.updateCompressionModel(selectedModel)
                "translation" -> vm.updateTranslationModel(selectedModel)
                "ocr" -> vm.updateOcrModel(selectedModel)
            }
            pickerTarget = null
        }

        var selectedTab by remember { mutableIntStateOf(0) }
        var customModelInput by remember { mutableStateOf("") }

        val cloudModels = listOf(
            "gemini-1.5-flash" to "Gemini 1.5 Flash (超快速)",
            "gemini-1.5-pro" to "Gemini 1.5 Pro (長上下文)",
            "gpt-4o" to "OpenAI GPT-4o (全能)",
            "gpt-4o-mini" to "OpenAI GPT-4o Mini (高效)",
            "claude-3-5-sonnet-20241022" to "Claude 3.5 Sonnet (推理)",
            "deepseek-chat" to "DeepSeek-V3 (開源旗艦)",
            "qwen2.5-72b-instruct" to "Qwen 2.5 72B (多語言)",
        )

        AlertDialog(
            onDismissRequest = { pickerTarget = null },
            title = { Text("選擇指定模型") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("主要/本機", style = MaterialTheme.typography.labelMedium) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("雲端 API", style = MaterialTheme.typography.labelMedium) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("自訂名稱", style = MaterialTheme.typography.labelMedium) }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    when (selectedTab) {
                        0 -> {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onSelect("use_current") }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = currentVal == "use_current",
                                        onClick = null,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text("使用目前對話模型", fontWeight = FontWeight.Medium)
                                        Text(
                                            "自動跟隨目前聊天中加載的模型",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                                if (localModels.isNotEmpty()) {
                                    Text("本地 GGUF 模型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.height(4.dp))
                                    localModels.forEach { model ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { onSelect(model.name) }
                                                .padding(vertical = 8.dp, horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(
                                                selected = currentVal == model.name,
                                                onClick = null,
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column {
                                                Text(model.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                                Text(String.format(java.util.Locale.US, "%.1f MB", model.sizeBytes / (1024.0 * 1024.0)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                } else {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ) {
                                        Text(
                                            "目前無本機 GGUF 模型，可在「模型」分頁下載或匯入。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                        1 -> {
                            Column {
                                Text("常用雲端模型 (需在 AI 提供商配置 API Key)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                cloudModels.forEach { (id, label) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onSelect(id) }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = currentVal == id,
                                            onClick = null,
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(id, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                                            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        2 -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("直接輸入欲使用的模型名稱或 ID：", style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(
                                    value = customModelInput,
                                    onValueChange = { customModelInput = it },
                                    label = { Text("模型名稱") },
                                    placeholder = { Text("例如：gpt-4o 或 llama-3.2-3b") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                TextButton(
                                    onClick = { if (customModelInput.isNotBlank()) onSelect(customModelInput.trim()) },
                                    enabled = customModelInput.isNotBlank(),
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("確認指定此模型")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerTarget = null }) { Text("關閉") }
            },
        )
    }

    // Role Config & Prompt Dialog (Optimized with Prompt Template + Temperature + Max Tokens)
    configTarget?.let { roleKey ->
        val roleTitle = when (roleKey) {
            "chat" -> "聊天模型"
            "titleSummary" -> "標題總結模型"
            "summary" -> "摘要模型"
            "suggestion" -> "聊天建議模型"
            "compression" -> "壓縮模型"
            "translation" -> "翻譯模型"
            "ocr" -> "OCR 模型"
            else -> "模型"
        }

        val defaultPrompt = when (roleKey) {
            "titleSummary" -> "請根據以下對話內容，總結出一個精簡且準確的標題（不超過15個字），不要加標點符號。"
            "summary" -> "請對以下對話生成精準精煉的摘要，保留關鍵上下文與用戶需求。"
            "suggestion" -> "根據對話上下文，提供3個用戶可能會繼續提問的簡短後續建議問題。"
            "compression" -> "將以下長對話歷史進行語意壓縮，保留所有重要決策、程式碼片段和關鍵事實。"
            "translation" -> "將以下內容精準翻譯為繁體中文，保留專業術語與原始格式。"
            "ocr" -> "精確辨識圖片中的文字與結構化內容，保留換行與表格格式。"
            else -> settings.startupPrompt
        }

        var currentPromptText by remember(roleKey) {
            mutableStateOf(
                when (roleKey) {
                    "titleSummary" -> settings.titleSummaryPrompt
                    "summary" -> settings.summaryPrompt
                    "suggestion" -> settings.chatSuggestionPrompt
                    "compression" -> settings.compressionPrompt
                    "translation" -> settings.translationPrompt
                    "ocr" -> settings.ocrPrompt
                    else -> settings.startupPrompt
                }
            )
        }

        var temp by remember { mutableStateOf(0.7f) }
        var maxTokens by remember { mutableStateOf(512) }

        AlertDialog(
            onDismissRequest = { configTarget = null },
            title = { Text("$roleTitle 參數與提示詞") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Prompt Template
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("自訂提示詞範本 (System Prompt)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            TextButton(
                                onClick = { currentPromptText = defaultPrompt },
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("恢復預設", fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = currentPromptText,
                            onValueChange = { currentPromptText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                            placeholder = { Text("輸入此角色的系統提示詞範本...") },
                        )
                    }

                    HorizontalDivider()

                    // Temperature Slider
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("溫度 (Temperature)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(String.format(java.util.Locale.US, "%.2f", temp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = temp,
                            onValueChange = { temp = it },
                            valueRange = 0.0f..1.5f,
                        )
                    }

                    // Max Tokens Slider
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("最大生成 Token", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text("$maxTokens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = maxTokens.toFloat(),
                            onValueChange = { maxTokens = it.toInt() },
                            valueRange = 64f..2048f,
                            steps = 30,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (roleKey) {
                            "titleSummary" -> vm.updateTitleSummaryPrompt(currentPromptText)
                            "summary" -> vm.updateSummaryPrompt(currentPromptText)
                            "suggestion" -> vm.updateChatSuggestionPrompt(currentPromptText)
                            "compression" -> vm.updateCompressionPrompt(currentPromptText)
                            "translation" -> vm.updateTranslationPrompt(currentPromptText)
                            "ocr" -> vm.updateOcrPrompt(currentPromptText)
                            "chat" -> vm.updateStartupPrompt(currentPromptText)
                        }
                        configTarget = null
                    }
                ) {
                    Text("儲存設定")
                }
            },
            dismissButton = {
                TextButton(onClick = { configTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ModelRoleCard(
    title: String,
    subtitle: String,
    currentModel: String,
    promptSnippet: String? = null,
    onSelectModel: () -> Unit,
    showDisableToggle: Boolean = false,
    isDisabled: Boolean = false,
    onToggleDisabled: () -> Unit = {},
    showRefresh: Boolean = false,
    onRefresh: () -> Unit = {},
    onConfigure: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showRefresh) {
                        IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = "切換狀態",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (showDisableToggle) {
                        IconButton(onClick = onToggleDisabled, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Outlined.Block,
                                contentDescription = if (isDisabled) "啟用" else "停用",
                                modifier = Modifier.size(20.dp),
                                tint = if (isDisabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (onConfigure != null) {
                        IconButton(onClick = onConfigure, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = "設定參數與提示詞",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
            )

            Spacer(Modifier.height(12.dp))

            // Pill button representing current selection
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSelectModel),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tagChar = when {
                        currentModel == "disabled" || isDisabled -> "未"
                        currentModel == "use_current" -> "使"
                        currentModel.contains("gemini") || currentModel.contains("gpt") || currentModel.contains("claude") || currentModel.contains("deepseek") || currentModel.contains("qwen") -> "雲"
                        else -> "本"
                    }
                    val displayText = when {
                        currentModel == "disabled" || isDisabled -> "未啟用"
                        currentModel == "use_current" -> "使用目前對話模型"
                        else -> currentModel
                    }

                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                when (tagChar) {
                                    "未" -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    "雲" -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                }
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            tagChar,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = when (tagChar) {
                                "未" -> MaterialTheme.colorScheme.onSurfaceVariant
                                "雲" -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            },
                        )
                    }

                    Spacer(Modifier.width(10.dp))
                    Text(
                        displayText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Prompt Snippet Preview
            if (promptSnippet != null && !isDisabled && currentModel != "disabled") {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Description,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            promptSnippet,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
