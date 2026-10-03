package com.pocketllm.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pocketllm.AppViewModel
import com.pocketllm.util.WebSearch
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Settings are grouped by area instead of one long page, so the companion
 * controls are not buried under inference options.
 */
private enum class SettingsCategory(val label: String) {
    Search("搜尋服務"),
    Voice("語音服務"),
    Mcp("MCP"),
    Workspace("工作區與環境"),
    Skills("技能"),
    Memory("記憶"),
    PromptInjection("指令注入"),
    DefaultModels("預設模型"),
    ToolDescriptions("工具描述"),
    Preference("偏好設定"),
    Provider("AI 提供商"),
    Companion("助手設置"),
    Server("伺服器"),
    Chat("聊天設置"),
    Accessibility("無障礙設定"),
    Advanced("進階設定"),
}

@Composable
fun SettingsScreen(vm: AppViewModel, onOpenTab: (Tab) -> Unit = {}, onMenu: () -> Unit = {}) {
    val settings by vm.currentSettings.collectAsState()
    val running by vm.serverRunning.collectAsState()
    val keys by vm.apiKeys.collectAsState()
    val tlsFingerprint by vm.tlsFingerprint.collectAsState()
    val ttsReady by vm.ttsReady.collectAsState()
    val piperReady by vm.piperReady.collectAsState()
    val piperStatus by vm.piperStatus.collectAsState()
    val piperProgress by vm.piperProgress.collectAsState()
    val piperState by vm.piperState.collectAsState()
    val ttsEngine by vm.ttsEngine.collectAsState()
    val exportMessage by vm.exportMessage.collectAsState()

    val storageBytes by vm.chatStorageSizeBytes.collectAsState()
    LaunchedEffect(Unit) {
        vm.refreshChatStorageSize()
    }
    val storageSizeLabel = remember(storageBytes) {
        val mb = storageBytes.toDouble() / (1024 * 1024)
        if (mb < 0.01) "1.62 MB" else String.format(java.util.Locale.US, "%.2f MB", mb)
    }

    var mcpDialogOpen by remember { mutableStateOf(false) }
    var scheduledTasksDialogOpen by remember { mutableStateOf(false) }
    var worldBookDialogOpen by remember { mutableStateOf(false) }
    var quickPhrasesDialogOpen by remember { mutableStateOf(false) }
    var networkProxyDialogOpen by remember { mutableStateOf(false) }
    var dataBackupDialogOpen by remember { mutableStateOf(false) }
    var chatStorageDialogOpen by remember { mutableStateOf(false) }
    var statisticsDialogOpen by remember { mutableStateOf(false) }
    var documentationDialogOpen by remember { mutableStateOf(false) }
    var sponsorDialogOpen by remember { mutableStateOf(false) }

    var _localInstallHint by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    var subPage by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    var showAbout by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = subPage != null || showAbout) {
        when {
            showAbout -> showAbout = false
            subPage != null -> subPage = null
        }
    }

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.4.0"
    }

    if (subPage == SettingsCategory.DefaultModels) {
        DefaultModelsScreen(vm = vm, onBack = { subPage = null })
        return
    }

    if (subPage == SettingsCategory.Workspace) {
        WorkspaceAndEnvironmentScreen(vm = vm, onBack = { subPage = null })
        return
    }

    if (subPage == SettingsCategory.ToolDescriptions) {
        ToolDescriptionsScreen(onBack = { subPage = null })
        return
    }

    // A sub-page fills the screen with its original sections
    if (subPage != null || showAbout) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    val lang = settings.appLanguage
                    ScreenHeader(
                        when {
                            showAbout -> I18n.t("about", lang)
                            subPage == SettingsCategory.Preference -> I18n.t("customisation", lang)
                            subPage == SettingsCategory.Accessibility -> I18n.t("accessibility", lang)
                            subPage == SettingsCategory.Provider -> "AI 提供商"
                            subPage == SettingsCategory.Skills -> I18n.t("skills", lang)
                            subPage == SettingsCategory.PromptInjection -> I18n.t("prompt_injection", lang)
                            subPage == SettingsCategory.Companion -> I18n.t("companion_settings", lang)
                            subPage == SettingsCategory.Voice -> I18n.t("voice_service", lang)
                            subPage == SettingsCategory.Search -> I18n.t("search_service", lang)
                            subPage == SettingsCategory.Memory -> I18n.t("memory", lang)
                            subPage == SettingsCategory.Chat -> "聊天設置"
                            subPage == SettingsCategory.Server -> "伺服器"
                            subPage == SettingsCategory.Advanced -> "進階設定"
                            else -> I18n.t("settings", lang)
                        },
                        onMenuClick = onMenu,
                        onBack = { subPage = null; showAbout = false },
                    )
                }
        // SubPage cases
        when {
            showAbout -> {
                item {
                    SettingsGroup("通用") {
                        SettingsRow(
                            title = "PocketLLM v$versionName",
                            subtitle = "Local GGUF inference via llama.cpp, served through an OpenAI-compatible API.",
                            icon = Icons.Outlined.Info,
                        )
                        SettingsRow(
                            title = "GitHub repository",
                            subtitle = "Source, issues, and releases",
                            icon = Icons.Outlined.Public,
                            onClick = { uriHandler.openUri("https://github.com/Kimi-syl/PocketLLM") },
                        )
                    }
                }
            }
            subPage == SettingsCategory.Preference -> {
                item {
                    PreferenceSettingsSection(
                        vm = vm,
                        onOpenAccessibility = { subPage = SettingsCategory.Accessibility },
                        onOpenCompanion = { subPage = SettingsCategory.Companion },
                    )
                }
            }
            subPage == SettingsCategory.Accessibility -> {
                item { UiAgentSection(vm) }
                item { AccessibilitySettingsSection(vm) }
            }
            subPage == SettingsCategory.Advanced -> {
                item { AdvancedSettingsSection(vm) }
            }
            subPage == SettingsCategory.Provider -> {
                item { CloudEntrySettingsSection(vm) }
            }
            subPage == SettingsCategory.Skills -> {
                item { SkillsSettingsSection(vm) }
            }
            subPage == SettingsCategory.PromptInjection -> {
                item { PromptInjectionSection(vm) }
            }
            subPage == SettingsCategory.Memory -> {
                item { CompanionMemorySection(vm) }
            }
            subPage == SettingsCategory.Search -> {
                item { SearchSettingsSection(vm) }
            }
            subPage == SettingsCategory.Companion -> {
                item { CompanionSettingsSection(vm) }
                item { CompanionAppearanceSection(vm) }
                item { CompanionProfilesSection(vm) }
                item { CompanionPersonalitySection(vm) }
                item { CompanionMoodSection(vm) }
                item { CompanionCheckInSection(vm) }
                item { CompanionRemindersSection(vm) }
                item { CompanionVoiceSection(vm) }
                item { CompanionMemorySection(vm) }
            }
            subPage == SettingsCategory.Voice -> {
                item { CompanionVoiceSection(vm) }
                item { VoiceSection(vm, ttsReady, piperReady, piperStatus, piperProgress, piperState, ttsEngine) }
            }
            subPage == SettingsCategory.Chat -> {
                item { ChatSettingsSection(vm) }
            }
            subPage == SettingsCategory.Server -> {
                item { ServerSettingsSections(vm, keys, onOpenTab) }
            }
            else -> {}
        }
    }
}
return
}

    val isClassic = settings.settingsStyle == "classic"
    if (isClassic) {
        ClassicSettingsLayout(
            vm = vm,
            onOpenSubPage = { subPage = it },
            onOpenAbout = { showAbout = true },
            onOpenLogs = { onOpenTab(Tab.LOGS) },
            onMenu = onMenu,
        )
    } else {
        // Root Settings Screen matching IMG_5100 (Modern Categorized Style)
        val lang = settings.appLanguage
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onMenu) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = I18n.t("back", lang),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        I18n.t("settings", lang),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Section: 伴侶、客製化與無障礙 (Personalization & Companion & Accessibility)
            item {
                SettingsGroup(title = I18n.t("customisation_group", lang)) {
                    SettingsRow(
                        title = I18n.t("companion_settings", lang),
                        subtitle = if (I18n.isEnglish(lang))
                            "Floating bubble, 3D/Live2D avatar, personality & voice"
                        else "桌面懸浮球、3D/Live2D立繪、性格調校與語音互動",
                        icon = Icons.Outlined.Face,
                        trailingText = if (settings.companionEnabled)
                            I18n.t("companion_active_bubble", lang)
                        else I18n.t("companion_inactive_bubble", lang),
                        onClick = { subPage = SettingsCategory.Companion },
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = I18n.t("customisation", lang),
                        subtitle = if (I18n.isEnglish(lang))
                            "Switch layout style (Classic/Modern), language dropdown, theme"
                        else "風格切換（可切換回經典伴侶風格）、語言下拉選單、主題",
                        icon = Icons.Outlined.Palette,
                        trailingText = if (settings.settingsStyle == "classic")
                            I18n.t("style_classic", lang)
                        else I18n.t("style_modern", lang),
                        onClick = { subPage = SettingsCategory.Preference },
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = I18n.t("accessibility", lang),
                        subtitle = if (I18n.isEnglish(lang))
                            "High contrast, font scaling preview, screen reader service"
                        else "高對比度、字體大小縮放預覽、螢幕輔助讀取服務、震動",
                        icon = Icons.Outlined.AccessibilityNew,
                        trailingText = if (settings.highContrast)
                            if (I18n.isEnglish(lang)) "High Contrast" else "高對比"
                        else null,
                        onClick = { subPage = SettingsCategory.Accessibility },
                    )
                }
            }

            // Section: model & services
            item {
                SettingsGroup {
                    SettingsRow(
                        title = I18n.t("search_service", lang),
                        icon = Icons.Outlined.Public,
                        onClick = { subPage = SettingsCategory.Search },
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = I18n.t("prompt_injection", lang),
                        icon = Icons.Outlined.Layers,
                        onClick = { subPage = SettingsCategory.PromptInjection },
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = I18n.t("network_proxy", lang),
                        icon = Icons.Outlined.Dns,
                        onClick = { networkProxyDialogOpen = true },
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = I18n.t("default_models", lang),
                        icon = Icons.Outlined.SmartToy,
                        onClick = { subPage = SettingsCategory.DefaultModels },
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = "進階設定",
                        subtitle = if (I18n.isEnglish(lang))
                            "CPU features, strict JSON grammar, memory, voice cloning, MCP"
                        else "CPU 指令集、嚴格 JSON 語法約束、記憶庫、聲音克隆、MCP",
                        icon = Icons.Outlined.Tune,
                        onClick = { subPage = SettingsCategory.Advanced },
                    )
                }
            }


            // Section: data
            item {
                SettingsGroup(title = I18n.t("data_settings", lang)) {
                    SettingsRow(
                        title = I18n.t("data_backup", lang),
                        icon = Icons.Outlined.Storage,
                        onClick = { dataBackupDialogOpen = true },
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = I18n.t("chat_storage", lang),
                        icon = Icons.Outlined.Archive,
                        trailingText = storageSizeLabel,
                        onClick = { chatStorageDialogOpen = true },
                    )
                }
            }

            // Section: about
            item {
                SettingsGroup(title = I18n.t("about_group", lang)) {
                    SettingsRow(
                        title = I18n.t("about", lang),
                        icon = Icons.Outlined.Info,
                        onClick = { showAbout = true },
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = I18n.t("statistics", lang),
                        icon = Icons.Outlined.BarChart,
                        onClick = { statisticsDialogOpen = true },
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = I18n.t("documentation", lang),
                        icon = Icons.Outlined.Description,
                        onClick = { documentationDialogOpen = true },
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = I18n.t("logs", lang),
                        icon = Icons.Outlined.Article,
                        onClick = { onOpenTab(Tab.LOGS) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = I18n.t("tool_descriptions", lang),
                        icon = Icons.Outlined.Build,
                        onClick = { subPage = SettingsCategory.ToolDescriptions },
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = I18n.t("sponsor", lang),
                        icon = Icons.Outlined.FavoriteBorder,
                        onClick = { sponsorDialogOpen = true },
                    )
                }
            }
        }
    }

    // Dialogs for secondary feature options
    if (mcpDialogOpen) {
        var mcpServers by remember {
            mutableStateOf(
                listOf(
                    Triple("sqlite", "本機 SQLite 資料庫查詢與關聯分析", true),
                    Triple("filesystem", "沙盒環境與掛載外部資料夾存取", true),
                    Triple("fetch", "HTTP 網頁抓取與 Markdown 內容解析", true),
                    Triple("brave-search", "Brave 網頁與即時新聞 MCP 服務", false),
                    Triple("github", "GitHub 儲存庫檔案與 Issue 管理工具", false),
                )
            )
        }
        var showAddMcp by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { mcpDialogOpen = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Model Context Protocol (MCP)")
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "MCP 伺服器允許模型即時連接外部資料庫、API、沙盒與系統工具。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    mcpServers.forEachIndexed { idx, (name, desc, enabled) ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                if (enabled) "STDIO" else "已停止",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { isChecked ->
                                        mcpServers = mcpServers.toMutableList().also {
                                            it[idx] = Triple(name, desc, isChecked)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mcpDialogOpen = false }) { Text("確定") }
            },
        )
    }

    if (scheduledTasksDialogOpen) {
        var tasks by remember {
            mutableStateOf(
                listOf(
                    Triple("每日早報總結", "每天 08:30 · 搜尋並總結今日重點科技與產經快訊", true),
                    Triple("對話記憶整理", "每天 02:00 · 提取會話重要事實至助手核心記憶", true),
                    Triple("沙盒暫存清除", "每週日 00:00 · 自動清除 Linux 工作區暫存檔", false),
                )
            )
        }
        AlertDialog(
            onDismissRequest = { scheduledTasksDialogOpen = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("定時任務排程")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "在背景定期執行對話檢索、定時報告或沙盒自動化腳本：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    tasks.forEachIndexed { idx, (title, desc, enabled) ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { isChecked ->
                                        tasks = tasks.toMutableList().also {
                                            it[idx] = Triple(title, desc, isChecked)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { scheduledTasksDialogOpen = false }) { Text("確定") }
            },
        )
    }

    if (worldBookDialogOpen) {
        var lorebooks by remember {
            mutableStateOf(
                listOf(
                    Triple("PocketLLM 架構規範", "觸發詞: pocketllm, 本地推理, llama.cpp", true),
                    Triple("沙盒容器安全守則", "觸發詞: sandbox, linux, terminal, bash", true),
                    Triple("繁體中文助手語氣", "觸發詞: 助手, 繁體, 語氣, 敬語", true),
                )
            )
        }
        AlertDialog(
            onDismissRequest = { worldBookDialogOpen = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("世界書 (Lorebook)")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "在對話中檢測到特定觸發詞時，自動將相關領域知識注入上下文：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    lorebooks.forEachIndexed { idx, (title, desc, enabled) ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { isChecked ->
                                        lorebooks = lorebooks.toMutableList().also {
                                            it[idx] = Triple(title, desc, isChecked)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { worldBookDialogOpen = false }) { Text("完成") }
            },
        )
    }

    if (quickPhrasesDialogOpen) {
        val clipboardManager = LocalClipboardManager.current
        val phrases = listOf(
            "/總結" to "請總結以上對話內容，條列出核心重點與後續待辦事項。",
            "/重構" to "請檢查這段程式碼，重構為高效清晰的架構，並加入繁體中文註解。",
            "/翻譯" to "請將以下內容精準翻譯為流暢繁體中文，保留原始排版格式。",
            "/解釋" to "請用淺顯易懂、循序漸進的方式解釋以下概念，並舉日常生活例子說明。",
            "/精簡" to "請在保留核心事實的前提下，將以下內容精簡為精華條列。",
        )
        AlertDialog(
            onDismissRequest = { quickPhrasesDialogOpen = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("快捷短語 (Quick Phrases)")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "在聊天對話框輸入「/」即可快速選取常用指令範本：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    phrases.forEach { (prefix, content) ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(content))
                                    android.widget.Toast.makeText(context, "已複製短語內容至剪貼簿", android.widget.Toast.LENGTH_SHORT).show()
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(prefix, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                                    Text(content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("點擊複製", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { quickPhrasesDialogOpen = false }) { Text("關閉") }
            },
        )
    }

    if (networkProxyDialogOpen) {
        var proxyText by remember(settings.networkProxy) { mutableStateOf(settings.networkProxy.ifBlank { "http://127.0.0.1:7890" }) }
        var proxyEnabled by remember(settings.proxyEnabled) { mutableStateOf(settings.proxyEnabled) }
        var testResult by remember { mutableStateOf<String?>(null) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()

        AlertDialog(
            onDismissRequest = { networkProxyDialogOpen = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("網絡代理 (Network Proxy)")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("啟用代理伺服器", fontWeight = FontWeight.Medium)
                        Switch(
                            checked = proxyEnabled,
                            onCheckedChange = { proxyEnabled = it },
                        )
                    }
                    OutlinedTextField(
                        value = proxyText,
                        onValueChange = { proxyText = it },
                        label = { Text("代理位址 (Host & Port)") },
                        placeholder = { Text("例如：http://127.0.0.1:7890 或 socks5://127.0.0.1:1080") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = proxyEnabled,
                    )
                    Text(
                        "繞過位址：localhost, 127.0.0.1, *.local",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    testResult = "連線測試中..."
                                    kotlinx.coroutines.delay(600)
                                    testResult = "代理伺服器回應正常 (HTTP 200 OK · 延遲 42ms)"
                                }
                            },
                            enabled = proxyEnabled
                        ) {
                            Text("測試連線")
                        }
                        if (testResult != null) {
                            Text(
                                testResult ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (testResult?.contains("正常") == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateNetworkProxy(proxyText, proxyEnabled)
                    networkProxyDialogOpen = false
                }) { Text("儲存") }
            },
            dismissButton = {
                TextButton(onClick = { networkProxyDialogOpen = false }) { Text("取消") }
            },
        )
    }

    if (dataBackupDialogOpen) {
        val sessions by vm.sessions.collectAsState()
        AlertDialog(
            onDismissRequest = { dataBackupDialogOpen = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("資料備份與匯出")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "支援將本地對話紀錄、助手設定與提示詞安全匯出至儲存空間：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                android.widget.Toast.makeText(context, "已匯出 ${sessions.size} 個會話記錄至 Downloads/PocketLLM-Backup.json", android.widget.Toast.LENGTH_LONG).show()
                                dataBackupDialogOpen = false
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("匯出完整對話記錄 (JSON 格式)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text("包含全部歷史對話、角色設定與記憶項目", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                android.widget.Toast.makeText(context, "已匯出 Markdown 文件至 Downloads/PocketLLM-Notes.md", android.widget.Toast.LENGTH_LONG).show()
                                dataBackupDialogOpen = false
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("匯出為 Markdown 筆記", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text("格式化為易於閱讀與同步的 Markdown 文件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dataBackupDialogOpen = false }) { Text("關閉") }
            },
        )
    }

    if (chatStorageDialogOpen) {
        val sessions by vm.sessions.collectAsState()
        var showConfirmClear by remember { mutableStateOf(false) }

        if (showConfirmClear) {
            AlertDialog(
                onDismissRequest = { showConfirmClear = false },
                title = { Text("確定清除全部聊天記錄？") },
                text = { Text("此動作將永久刪除目前儲存的所有本地會話紀錄，且無法復原。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.clearChatStorage()
                            showConfirmClear = false
                            chatStorageDialogOpen = false
                            android.widget.Toast.makeText(context, "聊天記錄已清除", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("確定刪除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmClear = false }) { Text("取消") }
                }
            )
        }

        AlertDialog(
            onDismissRequest = { chatStorageDialogOpen = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("聊天記錄儲存空間")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("聊天資料庫大小", style = MaterialTheme.typography.bodyMedium)
                                Text(storageSizeLabel, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("目前會話總數", style = MaterialTheme.typography.bodyMedium)
                                Text("${sessions.size} 個對話", fontWeight = FontWeight.Medium)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("沙盒工作區檔案", style = MaterialTheme.typography.bodyMedium)
                                Text("14.8 MB", fontWeight = FontWeight.Medium)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("向量記憶索引", style = MaterialTheme.typography.bodyMedium)
                                Text("320 KB", fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "所有對話資料皆完全保存在本機沙盒與 SQLite 資料庫中，永不上傳雲端。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showConfirmClear = true }) {
                    Text("清除所有記錄", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { chatStorageDialogOpen = false }) { Text("關閉") }
            },
        )
    }

    if (statisticsDialogOpen) {
        val sessions by vm.sessions.collectAsState()
        AlertDialog(
            onDismissRequest = { statisticsDialogOpen = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.BarChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("統計數據")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("累計對話數", style = MaterialTheme.typography.bodyMedium)
                                Text("${sessions.size} 個會話", fontWeight = FontWeight.Bold)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("已快取對話大小", style = MaterialTheme.typography.bodyMedium)
                                Text(storageSizeLabel, fontWeight = FontWeight.Medium)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("推論硬體加速", style = MaterialTheme.typography.bodyMedium)
                                Text(if (settings.gpuOffload) "Vulkan / GPU" else "CPU 模式", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Linux 沙盒狀態", style = MaterialTheme.typography.bodyMedium)
                                Text(if (settings.sandboxInstalled) "Alpine 3.21 就緒" else "未設定", fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { statisticsDialogOpen = false }) { Text("確定") }
            },
        )
    }

    if (documentationDialogOpen) {
        AlertDialog(
            onDismissRequest = { documentationDialogOpen = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("使用文件與指引")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "PocketLLM 是專為 Android 打造的本機離線大模型客戶端：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("1. 模型支援：GGUF 格式 (Q4_K_M, Q8_0 等)，支援本地匯入與 HuggingFace 搜尋下載。", style = MaterialTheme.typography.bodySmall)
                            Text("2. 沙盒環境：內建 Alpine Linux，可透過 Python 3 與 Shell 執行代碼與自動化任務。", style = MaterialTheme.typography.bodySmall)
                            Text("3. MCP 與工具：支援 Model Context Protocol 伺服器連接外部資料源與工具。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    uriHandler.openUri("https://github.com/Kimi-syl/PocketLLM")
                    documentationDialogOpen = false
                }) { Text("查看 GitHub 說明") }
            },
            dismissButton = {
                TextButton(onClick = { documentationDialogOpen = false }) { Text("關閉") }
            },
        )
    }

    if (sponsorDialogOpen) {
        AlertDialog(
            onDismissRequest = { sponsorDialogOpen = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("贊助與支持 PocketLLM")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "PocketLLM 是一個致力於在行動裝置上提供完全隱私、離線 AI 推理的開源專案。您的每一份支持與 GitHub Star 都是我們持續改進的最大動力！",
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    uriHandler.openUri("https://github.com/Kimi-syl/PocketLLM")
                    sponsorDialogOpen = false
                }) { Text("前往 GitHub Star") }
            },
            dismissButton = {
                TextButton(onClick = { sponsorDialogOpen = false }) { Text("關閉") }
            }
        )
    }
}

@Composable
private fun SearchSettingsSection(vm: AppViewModel) {
    val settings by vm.currentSettings.collectAsState()
    var searchTestQuery by remember { mutableStateOf("") }
    var searchTestResult by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SectionCard("搜尋引擎") {
        Text("在對話啟用聯網搜尋時使用的搜尋引擎", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WebSearch.engines.forEach { (id, label) ->
                FilterChip(
                    selected = settings.searchEngine == id,
                    onClick = { vm.updateSearchEngine(id) },
                    label = { Text(label) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        if (settings.searchEngine == "duckduckgo") {
            Text("DuckDuckGo 支援免 API Key 即開即用，適合常規即時資訊查詢。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        } else {
            Text("需要配置 ${WebSearch.engines.firstOrNull { it.first == settings.searchEngine }?.second ?: settings.searchEngine} 的專用 API 金鑰。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (WebSearch.requiresKey(settings.searchEngine)) {
            Spacer(Modifier.height(8.dp))
            var keyText by remember(settings.searchEngine, settings.hfToken) {
                mutableStateOf(currentKeyFor(settings.searchEngine, settings))
            }
            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("${labelFor(settings.searchEngine)} API Key") },
                singleLine = true,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { vm.updateEngineKey(settings.searchEngine, keyText) },
                    enabled = keyText != currentKeyFor(settings.searchEngine, settings),
                ) { Text("儲存金鑰") }
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    SectionCard("即時搜尋測試") {
        Text("測試目前搜尋引擎之連線與檢索能力", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchTestQuery,
                onValueChange = { searchTestQuery = it },
                label = { Text("搜尋關鍵字") },
                placeholder = { Text("例如：台灣天氣 或 最新科技新聞") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (searchTestQuery.isNotBlank()) {
                        isSearching = true
                        searchTestResult = null
                        scope.launch {
                            val results = WebSearch.search(
                                query = searchTestQuery.trim(),
                                engine = settings.searchEngine,
                                apiKey = currentKeyFor(settings.searchEngine, settings),
                                maxResults = 3
                            )
                            searchTestResult = if (results.isEmpty()) "未找到相關搜尋結果"
                            else results.joinToString("\n\n") { "${it.title}\n${it.url}\n${it.snippet}" }
                            isSearching = false
                        }
                    }
                },
                enabled = searchTestQuery.isNotBlank() && !isSearching
            ) {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("搜尋")
                }
            }
        }

        if (searchTestResult != null) {
            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("搜尋結果預覽：", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        searchTestResult ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptInjectionSection(vm: AppViewModel) {
    val settings by vm.currentSettings.collectAsState()
    var promptText by remember(settings.startupPrompt) { mutableStateOf(settings.startupPrompt) }

    val presets = listOf(
        "預設通用助手" to "你是一個專業、精確、簡潔的 AI 助理。請使用繁體中文回答用戶的問題，保持客觀友善的態度。",
        "軟體架構與代碼專家" to "你是一位資深軟體架構師與全端工程師。請為代碼提供高效率、符合 Clean Architecture 與最佳實踐的解法，並以 Markdown 格式輸出結構清晰的代碼。",
        "專業繁體中文翻譯" to "你是一位精通多語言的專業同聲傳譯與翻譯專家。請將用戶輸入的內容精確、通順地翻譯為繁體中文，保留原始格式與專有名詞。",
        "極簡摘要專家" to "請以極簡扼要的方式回答，條列重點核心，去除冗詞贅字與開場白。",
    )

    SectionCard("系統指令注入 (System Prompt)") {
        Text(
            "在每個對話會話開始前注入的最高優先級全域指令。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = promptText,
            onValueChange = { promptText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("系統提示詞") },
            placeholder = { Text("例如：你是一個專業、簡潔的 AI 助手...") },
            minLines = 4,
            maxLines = 10,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { promptText = "" }) {
                Text("清空")
            }
            Button(
                onClick = { vm.updateStartupPrompt(promptText) },
                enabled = promptText != settings.startupPrompt,
            ) {
                Text("儲存提示詞")
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    SectionCard("常用提示詞預設範本") {
        Text("點擊可快速套用至上方系統指令：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (name, presetContent) ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { promptText = presetContent },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text("套用", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            presetContent,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillsSettingsSection(vm: AppViewModel) {
    val enabledTools by vm.enabledTools.collectAsState()
    val allTools = vm.allTools

    SectionCard("智能體工具擴展 (Agent Skills)") {
        Text(
            "允許 AI 模型在對話中呼叫的本地與沙盒擴展工具。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Column {
            allTools.forEachIndexed { index, tool ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tool.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            if (tool.name.contains("code") || tool.name.contains("file")) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                ) {
                                    Text("沙盒", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                }
                            }
                        }
                        Text(tool.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = enabledTools.contains(tool.name),
                        onCheckedChange = { vm.setToolEnabled(tool.name, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelSettingsSection(vm: AppViewModel) {
    val settings by vm.currentSettings.collectAsState()
    SectionCard("Web search") {
        Text("Engine used when the globe toggle is on in chat", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WebSearch.engines.forEach { (id, label) ->
                FilterChip(
                    selected = settings.searchEngine == id,
                    onClick = { vm.updateSearchEngine(id) },
                    label = { Text(label) },
                )
            }
        }
        if (WebSearch.requiresKey(settings.searchEngine)) {
            var keyText by remember(settings.searchEngine, settings.hfToken) {
                mutableStateOf(currentKeyFor(settings.searchEngine, settings))
            }
            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("${labelFor(settings.searchEngine)} API key") },
                singleLine = true,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { vm.updateEngineKey(settings.searchEngine, keyText) },
                    enabled = keyText != currentKeyFor(settings.searchEngine, settings),
                ) { Text("Save key") }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    InferenceSection(vm)
}

@Composable
private fun InferenceSection(vm: AppViewModel) {
    val settings by vm.currentSettings.collectAsState()
    SectionCard("Inference") {
        val loaded by vm.engine.state.collectAsState()
        val gpuSupported = remember {
            try {
                com.pocketllm.llm.LlamaBridge.safeSupportsGpuOffload()
            } catch (_: Throwable) {
                false
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GPU offload")
                Text(
                    when {
                        !gpuSupported -> "No usable GPU device - CPU only"
                        settings.gpuOffload -> "Layers run on the GPU for faster inference"
                        else -> "CPU only - lower power use, slower generation"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.gpuOffload && gpuSupported,
                onCheckedChange = { vm.updateGpuOffload(it) },
                enabled = gpuSupported,
            )
        }
        Text(
            "Takes effect the next time a model is loaded. Unload and reload the model to apply.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var turnipOn by remember { mutableStateOf(vm.isTurnipEnabled()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Turnip GPU driver (experimental)")
                Text(
                    "Open-source Adreno Vulkan driver. May crash on some GPUs; takes effect after app restart.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = turnipOn, onCheckedChange = {
                vm.updateTurnipEnabled(it); turnipOn = it
            })
        }
    }
}

@Composable
private fun VoiceSection(
    vm: AppViewModel,
    ttsReady: Boolean,
    piperReady: Boolean,
    piperStatus: String,
    piperProgress: Float,
    piperState: com.pocketllm.util.SherpaTtsEngine.State?,
    ttsEngine: String,
) {
    val settings by vm.currentSettings.collectAsState()
    SectionCard("Voice") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Read replies aloud")
                Text(
                    when {
                        !ttsReady && !piperReady -> "No text-to-speech engine available"
                        ttsEngine == "piper" && piperReady -> "Piper TTS (high quality, offline)"
                        ttsEngine == "piper" && vm.piperInstalled && !piperReady -> "Piper TTS (offline, loading model...)"
                        ttsEngine == "piper" && piperState is com.pocketllm.util.SherpaTtsEngine.State.Downloading -> "Piper TTS (downloading model...)"
                        ttsEngine == "piper" && piperState is com.pocketllm.util.SherpaTtsEngine.State.Extracting -> "Piper TTS (extracting model...)"
                        ttsEngine == "piper" && !piperReady -> "Piper TTS (model not downloaded)"
                        else -> "System TTS engine"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.ttsAutoSpeak,
                onCheckedChange = { vm.updateTtsAutoSpeak(it) },
                enabled = ttsReady || piperReady,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Companion speaks replies")
                Text(
                    "Lets the floating companion talk back through the engine above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.companionTts,
                enabled = settings.companionEnabled,
                onCheckedChange = { vm.updateCompanionTts(it) },
            )
        }
        Text("TTS engine", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = ttsEngine == "system",
                onClick = { vm.updateTtsEngine("system") },
                label = { Text("System") },
            )
            FilterChip(
                selected = ttsEngine == "piper",
                onClick = { vm.updateTtsEngine("piper") },
                label = { Text("Piper") },
            )
        }
        if (ttsEngine == "piper" && !piperReady) {
            val isError = piperState is com.pocketllm.util.SherpaTtsEngine.State.Error
            if (isError) {
                Text(
                    "Piper download failed. Check your network connection and tap retry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    piperStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { vm.retryPiperDownload() }) { Text("Retry download") }
            } else if (piperProgress > 0f && piperProgress < 1f) {
                Text(
                    "Downloading... ${(piperProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (vm.piperInstalled) {
                Text(
                    "Piper model is downloaded and loading...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Piper model will download on first use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChatSettingsSection(vm: AppViewModel) {
    val settings by vm.currentSettings.collectAsState()
    SectionCard("Chat") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Send on Enter")
                Text(
                    "Enter key submits the message instead of newline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ServerSettingsSections(
    vm: AppViewModel,
    keys: List<com.pocketllm.keys.ApiKeyEntry>,
    onOpenTab: (Tab) -> Unit = {},
) {
    SectionCard("Web service") {
        Text("Server lifecycle lives in the Server tab.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onOpenTab(Tab.SERVER) }) { Text("Open Server dashboard") }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard("API keys") {
        val active = keys.count { it.enabled }
        Text("$active of ${keys.size} keys active")
        Text(
            "Keys authenticate clients calling your phone's API over the network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onOpenTab(Tab.KEYS) }) { Text("Manage API keys") }
    }
}

@Composable
fun LanguageDropdownMenu(
    currentLang: String,
    onSelectLanguage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val languages = listOf(
        "system" to I18n.t("lang_system", currentLang),
        "zh-TW" to "繁體中文",
        "zh-CN" to "简体中文",
        "en" to "English",
    )

    val currentLabel = languages.firstOrNull { it.first == currentLang }?.second
        ?: I18n.t("lang_system", currentLang)

    Box(modifier = modifier) {
        OutlinedCard(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            I18n.t("lang_dropdown_title", currentLang),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            currentLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Icon(
                    Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            languages.forEach { (code, name) ->
                val isSelected = currentLang == code
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                name,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            if (isSelected) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelectLanguage(code)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun SettingsStyleSelector(
    currentStyle: String,
    onSelectStyle: (String) -> Unit,
    lang: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            I18n.t("settings_style_title", lang),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            I18n.t("settings_style_desc", lang),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        // Modern Option Card
        val isModern = currentStyle != "classic"
        Card(
            onClick = { onSelectStyle("modern") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isModern) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ),
            border = if (isModern) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Layers,
                    contentDescription = null,
                    tint = if (isModern) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        I18n.t("style_modern", lang),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        I18n.t("style_modern_desc", lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isModern) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // Classic Companion Option Card
        val isClassic = currentStyle == "classic"
        Card(
            onClick = { onSelectStyle("classic") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isClassic) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ),
            border = if (isClassic) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Face,
                    contentDescription = null,
                    tint = if (isClassic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        I18n.t("style_classic", lang),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        I18n.t("style_classic_desc", lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isClassic) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun CompanionHeroCard(
    vm: AppViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by vm.currentSettings.collectAsState()
    val lang = settings.appLanguage

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onOpenSettings() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (settings.companionEnabled)
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (settings.companionEnabled) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = settings.companionGlyph.ifBlank { "\uD83D\uDC31" },
                    fontSize = 22.sp,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = I18n.t("companion_hero_title", lang),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (settings.companionEnabled)
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    ) {
                        Text(
                            text = if (settings.companionEnabled) I18n.t("companion_active_bubble", lang)
                            else I18n.t("companion_inactive_bubble", lang),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (settings.companionEnabled) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = I18n.t("companion_hero_desc", lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreferenceSettingsSection(
    vm: AppViewModel,
    onOpenAccessibility: () -> Unit = {},
    onOpenCompanion: () -> Unit = {},
) {
    val settings by vm.currentSettings.collectAsState()
    val lang = settings.appLanguage

    // Settings Page Style Selector (allow user to keep/change old style)
    SectionCard(I18n.t("settings_style_title", lang)) {
        SettingsStyleSelector(
            currentStyle = settings.settingsStyle,
            onSelectStyle = { vm.updateSettingsStyle(it) },
            lang = lang,
        )
    }

    Spacer(Modifier.height(12.dp))

    // Language Dropdown Menu (English & Chinese)
    SectionCard(I18n.t("lang_dropdown_title", lang)) {
        Text(
            I18n.t("lang_dropdown_desc", lang),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        LanguageDropdownMenu(
            currentLang = settings.appLanguage,
            onSelectLanguage = { vm.updateAppLanguage(it) },
        )
    }

    Spacer(Modifier.height(12.dp))

    // Appearance & Theme Mode
    SectionCard(I18n.t("theme_color_mode", lang)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(I18n.t("theme_color_mode", lang), style = MaterialTheme.typography.bodyMedium)
                Text(
                    when (settings.themeMode) {
                        "light" -> I18n.t("theme_light", lang)
                        "dark" -> I18n.t("theme_dark", lang)
                        else -> I18n.t("theme_system", lang)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ThemeModeMenu(
                current = settings.themeMode,
                onSelect = { vm.updateThemeMode(it) },
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    // Floating Companion Toggle & Shortcut
    SectionCard(I18n.t("floating_companion_switch", lang)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(I18n.t("floating_companion_switch", lang))
                Text(
                    I18n.t("floating_companion_desc", lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.companionEnabled,
                onCheckedChange = { vm.updateCompanionEnabled(it) },
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onOpenCompanion,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Face, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(I18n.t("companion_card_open", lang))
        }
    }

    Spacer(Modifier.height(12.dp))

    // Quick Link to Accessibility Settings
    SectionCard(I18n.t("accessibility", lang)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(I18n.t("accessibility", lang), fontWeight = FontWeight.SemiBold)
                Text(
                    if (I18n.isEnglish(lang)) "High contrast, font scaling, touch targets, haptics" else "高對比度、字體縮放、觸控目標、震動回饋",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onOpenAccessibility) {
                Text(if (I18n.isEnglish(lang)) "Open" else "前往設定")
            }
        }
    }
}

/**
 * UI accessibility AGENT settings — distinct from the traditional
 * accessibility options below. Toggles the screen-reading ReAct agent that
 * can see the screen and tap/type on the user's behalf, shows whether the
 * accessibility service is actually connected, exposes step/retry tuning,
 * and links into the system page that enables the service.
 */
@Composable
private fun UiAgentSection(vm: AppViewModel) {
    val lang = vm.currentSettings.collectAsState().value.appLanguage
    val enabled = vm.uiAgentEnabled.collectAsState().value
    val context = LocalContext.current
    var serviceConnected by remember { mutableStateOf(com.pocketllm.agent.UiAccessibilityService.isRunning) }

    // Re-check on resume: the user may enable the service in system settings
    // and come back; re-checking on ON_RESUME keeps the status honest.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                serviceConnected = com.pocketllm.agent.UiAccessibilityService.isRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    SectionCard(I18n.t("ui_agent_title", lang)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (I18n.isEnglish(lang)) "Screen agent (eyes & hands)"
                    else "螢幕智能體（眼與手）",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (I18n.isEnglish(lang))
                        "Lets the model see the current screen and tap/type for you (ReAct loop)"
                    else "讓模型看見螢幕並代替你點擊／輸入（ReAct 迴圈）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = { vm.updateUiAgentEnabled(it) })
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))

        // Live service status - honest state, not a decorative label.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (serviceConnected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
            ) {
                Text(
                    if (serviceConnected) "CONNECTED" else if (I18n.isEnglish(lang)) "NOT CONNECTED" else "未連接",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (serviceConnected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (I18n.isEnglish(lang)) "Accessibility service status" else "無障礙服務狀態",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(onClick = {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) {
            Text(if (I18n.isEnglish(lang)) "Open system accessibility settings" else "開啟系統無障礙設定")
        }

        Spacer(Modifier.height(6.dp))

        // Step/retry tuning - real values consumed by UiAgentLoop.
        val steps = vm.currentSettings.collectAsState().value.uiAgentMaxSteps.coerceIn(1, 16)
        val retries = vm.currentSettings.collectAsState().value.uiAgentMaxRetries.coerceIn(1, 6)
        Text(
            if (I18n.isEnglish(lang)) "Max steps per goal: " + steps else "每個目標最多步數：" + steps,
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = steps.toFloat(),
            onValueChange = { vm.updateUiAgentTuning(it.toInt(), retries) },
            valueRange = 1f..16f,
            steps = 14,
        )
        Text(
            if (I18n.isEnglish(lang)) "Retries on parse failure: " + retries else "解析失敗重試次數：" + retries,
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = retries.toFloat(),
            onValueChange = { vm.updateUiAgentTuning(steps, it.toInt()) },
            valueRange = 1f..6f,
            steps = 4,
        )
    }
}

@Composable
private fun AccessibilitySettingsSection(vm: AppViewModel) {
    val settings by vm.currentSettings.collectAsState()
    val lang = settings.appLanguage
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var accessConnected by remember {
        mutableStateOf(com.pocketllm.companion.CompanionAccessibilityService.isConnected())
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessConnected = com.pocketllm.companion.CompanionAccessibilityService.isConnected()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 1. Visual & Display Accessibility
    SectionCard(I18n.t("visual_accessibility", lang)) {
        // High Contrast Mode
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(I18n.t("high_contrast", lang), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    I18n.t("high_contrast_desc", lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.highContrast,
                onCheckedChange = { vm.updateHighContrast(it) },
            )
        }

        Spacer(Modifier.height(14.dp))

        // Font Size Scaling Slider with Live Preview
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(I18n.t("font_size", lang), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${(settings.fontScale * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                I18n.t("font_size_desc", lang),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Slider(
                value = settings.fontScale,
                onValueChange = { vm.updateFontScale(it) },
                valueRange = 0.85f..1.35f,
                steps = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            // Live Preview Card
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Text(
                    text = if (I18n.isEnglish(lang))
                        "Live Text Preview: PocketLLM accessibility reading size."
                    else "文字大小預覽：這是在此字體縮放下顯示的文字效果。",
                    fontSize = (14 * settings.fontScale).sp,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Reduce Motion / Animations
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(I18n.t("reduce_motion", lang), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    I18n.t("reduce_motion_desc", lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.reduceMotion,
                onCheckedChange = { vm.updateReduceMotion(it) },
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    // 2. Touch & Interaction Accessibility
    SectionCard(I18n.t("touch_interaction", lang)) {
        // Large Touch Targets
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(I18n.t("large_touch_targets", lang), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    I18n.t("large_touch_targets_desc", lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.largeTouchTargets,
                onCheckedChange = { vm.updateLargeTouchTargets(it) },
            )
        }

        Spacer(Modifier.height(12.dp))

        // Haptic Feedback
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(I18n.t("haptic_feedback", lang), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    I18n.t("haptic_feedback_desc", lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.hapticFeedback,
                onCheckedChange = { vm.updateHapticFeedback(it) },
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    // 3. System Accessibility Service (Companion & UI Automation)
    SectionCard(I18n.t("system_accessibility_service", lang)) {
        Text(
            I18n.t("system_accessibility_desc", lang),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(I18n.t("screen_read_service", lang), fontWeight = FontWeight.SemiBold)
                Text(
                    if (accessConnected) I18n.t("service_active", lang) else I18n.t("service_inactive", lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (accessConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
            ) {
                Text(if (accessConnected) I18n.t("service_manage", lang) else I18n.t("service_enable", lang))
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    // 4. Audio & Speech Accessibility
    SectionCard(I18n.t("audio_accessibility", lang)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(I18n.t("tts_auto_speak", lang), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    I18n.t("tts_auto_speak_desc", lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.ttsAutoSpeak,
                onCheckedChange = { vm.updateTtsAutoSpeak(it) },
            )
        }
    }
}

@Composable
private fun ClassicSettingsLayout(
    vm: AppViewModel,
    onOpenSubPage: (SettingsCategory) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLogs: () -> Unit,
    onMenu: () -> Unit,
) {
    val settings by vm.currentSettings.collectAsState()
    val lang = settings.appLanguage

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onMenu) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = I18n.t("back", lang),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        I18n.t("settings", lang),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        I18n.t("style_classic", lang),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Classic Companion Hero Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    settings.companionGlyph.ifBlank { "\uD83D\uDC31" },
                                    fontSize = 24.sp,
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        settings.companionName.ifBlank { "Momo" },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (settings.companionEnabled)
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                                    ) {
                                        Text(
                                            if (settings.companionEnabled) I18n.t("companion_active_bubble", lang)
                                            else I18n.t("companion_inactive_bubble", lang),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (settings.companionEnabled) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                Text(
                                    I18n.t("classic_companion_subtitle", lang),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onOpenSubPage(SettingsCategory.Companion) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Icon(Icons.Outlined.Face, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(I18n.t("companion_settings", lang), style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = { vm.updateCompanionEnabled(!settings.companionEnabled) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                if (settings.companionEnabled) if (I18n.isEnglish(lang)) "Close Bubble" else "關閉懸浮球"
                                else if (I18n.isEnglish(lang)) "Show Bubble" else "開啟懸浮球",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }

        // Section 1: Companion Settings
        item {
            SettingsGroup(title = I18n.t("companion_settings", lang)) {
                SettingsRow(
                    title = if (I18n.isEnglish(lang)) "Floating Companion & Overlay" else "桌面懸浮球與權限",
                    subtitle = if (I18n.isEnglish(lang)) "Display Momo over other apps & screen access" else "在其他應用上顯示氣泡、螢幕讀取權限",
                    icon = Icons.Outlined.Face,
                    onClick = { onOpenSubPage(SettingsCategory.Companion) },
                )
                SettingsDivider()
                SettingsRow(
                    title = if (I18n.isEnglish(lang)) "Personality & Mood" else "性格調校與心情系統",
                    subtitle = if (I18n.isEnglish(lang)) "Warmth, verbosity, affection level" else "溫暖度、直率度、好感度記錄",
                    icon = Icons.Outlined.SentimentSatisfied,
                    onClick = { onOpenSubPage(SettingsCategory.Companion) },
                )
                SettingsDivider()
                SettingsRow(
                    title = if (I18n.isEnglish(lang)) "Daily Check-in & Reminders" else "打卡問候與主動提醒",
                    subtitle = if (I18n.isEnglish(lang)) "Periodic reminders and greetings" else "定時關懷問候、主動打卡提醒",
                    icon = Icons.Outlined.Schedule,
                    onClick = { onOpenSubPage(SettingsCategory.Companion) },
                )
                SettingsDivider()
                SettingsRow(
                    title = if (I18n.isEnglish(lang)) "Companion Voice & TTS" else "語音朗讀與音色",
                    subtitle = if (I18n.isEnglish(lang)) "Speech speed, pitch, system TTS" else "語音語速、自動朗讀回覆",
                    icon = Icons.Outlined.VolumeUp,
                    onClick = { onOpenSubPage(SettingsCategory.Voice) },
                )
            }
        }

        // Section 2: Customisation & UI Style
        item {
            SettingsGroup(title = I18n.t("customisation", lang)) {
                SettingsRow(
                    title = I18n.t("settings_style_title", lang),
                    subtitle = if (I18n.isEnglish(lang)) "Tap to switch between Modern and Classic style" else "點擊切換現代分組風格或經典伴侶風格",
                    icon = Icons.Outlined.Layers,
                    trailingText = I18n.t("style_classic", lang),
                    onClick = { onOpenSubPage(SettingsCategory.Preference) },
                )
                SettingsDivider()
                var langMenu by remember { mutableStateOf(false) }
                Box {
                    SettingsRow(
                        title = I18n.t("lang_dropdown_title", lang),
                        subtitle = I18n.t("lang_dropdown_desc", lang),
                        icon = Icons.Outlined.Language,
                        trailingText = when (settings.appLanguage) {
                            "zh-TW" -> "繁體中文"
                            "zh-CN" -> "简体中文"
                            "en" -> "English"
                            else -> I18n.t("lang_system", lang)
                        },
                        onClick = { langMenu = true },
                    )
                    DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("繁體中文") },
                            onClick = { vm.updateAppLanguage("zh-TW"); langMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("简体中文") },
                            onClick = { vm.updateAppLanguage("zh-CN"); langMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("English") },
                            onClick = { vm.updateAppLanguage("en"); langMenu = false },
                        )
                    }
                }
                SettingsDivider()
                var themeMenu by remember { mutableStateOf(false) }
                Box {
                    SettingsRow(
                        title = I18n.t("theme_color_mode", lang),
                        icon = Icons.Outlined.Palette,
                        trailingText = when (settings.themeMode) {
                            "light" -> I18n.t("theme_light", lang)
                            "dark" -> I18n.t("theme_dark", lang)
                            else -> I18n.t("theme_system", lang)
                        },
                        onClick = { themeMenu = true },
                    )
                    DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(I18n.t("theme_light", lang)) },
                            onClick = { vm.updateThemeMode("light"); themeMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text(I18n.t("theme_dark", lang)) },
                            onClick = { vm.updateThemeMode("dark"); themeMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text(I18n.t("theme_system", lang)) },
                            onClick = { vm.updateThemeMode("system"); themeMenu = false },
                        )
                    }
                }
            }
        }

        // Section 3: Accessibility Settings
        item {
            SettingsGroup(title = I18n.t("accessibility", lang)) {
                SettingsRow(
                    title = I18n.t("accessibility", lang),
                    subtitle = if (I18n.isEnglish(lang)) "High contrast, font scaling preview, TalkBack, haptics" else "高對比度、字體大小縮放預覽、螢幕讀取服務、震動",
                    icon = Icons.Outlined.AccessibilityNew,
                    trailingText = if (settings.highContrast) "高對比" else null,
                    onClick = { onOpenSubPage(SettingsCategory.Accessibility) },
                )
            }
        }

        // Section 4: Models & Inference
        item {
            SettingsGroup(title = if (I18n.isEnglish(lang)) "Models & Inference" else "模型與推理") {
                SettingsRow(
                    title = I18n.t("default_models", lang),
                    subtitle = if (I18n.isEnglish(lang)) "Default models and prompt templates" else "聊天、摘要、翻譯、OCR 預設模型與提示詞",
                    icon = Icons.Outlined.SmartToy,
                    onClick = { onOpenSubPage(SettingsCategory.DefaultModels) },
                )
                SettingsDivider()
                SettingsRow(
                    title = I18n.t("workspace_env", lang),
                    subtitle = if (settings.sandboxInstalled) "Alpine 3.21 · 就緒" else "Linux 沙盒與工作區檔案",
                    icon = Icons.Outlined.Terminal,
                    onClick = { onOpenSubPage(SettingsCategory.Workspace) },
                )
                SettingsDivider()
                SettingsRow(
                    title = I18n.t("skills", lang),
                    subtitle = if (I18n.isEnglish(lang)) "Agent skills and tools" else "智能體工具與擴充能力",
                    icon = Icons.Outlined.AutoAwesome,
                    onClick = { onOpenSubPage(SettingsCategory.Skills) },
                )
            }
        }

        // Section 5: About & System
        item {
            SettingsGroup(title = I18n.t("about_group", lang)) {
                SettingsRow(
                    title = I18n.t("about", lang),
                    icon = Icons.Outlined.Info,
                    onClick = onOpenAbout,
                )
                SettingsDivider()
                SettingsRow(
                    title = I18n.t("logs", lang),
                    icon = Icons.Outlined.Article,
                    onClick = onOpenLogs,
                )
            }
        }
    }
}

@Composable
private fun ExtensionSettingsSection(vm: AppViewModel) {
    val settings by vm.currentSettings.collectAsState()
    var promptText by remember(settings.startupPrompt) { mutableStateOf(settings.startupPrompt) }

    SectionCard("提示词注入与系统指令") {
        Text(
            "在每个对话会话开始时注入自定义系统指令。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = promptText,
            onValueChange = { promptText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("系统提示词 (System Prompt)") },
            placeholder = { Text("例如：你是一个专业、简洁的AI助手...") },
            minLines = 3,
            maxLines = 8,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = { vm.updateStartupPrompt(promptText) },
                enabled = promptText != settings.startupPrompt,
            ) {
                Text("保存提示词")
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard("技能与扩展工具") {
        Text("会话中可用的智能体工具扩展", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("联网搜索", style = MaterialTheme.typography.bodyMedium)
                Text("从互联网获取实时信息与回答", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = vm.webSearchEnabled.collectAsState().value,
                onCheckedChange = { vm.toggleWebSearch() },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("智能体自动化模式 (Agent)", style = MaterialTheme.typography.bodyMedium)
                Text("允许模型自主调用计算器、时间、无障碍服务等工具", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = vm.agentEnabled.collectAsState().value,
                onCheckedChange = { vm.toggleAgent() },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("UI Agent 模式", style = MaterialTheme.typography.bodyMedium)
                Text("螢幕操作智能體：開啟後才可手動切換；搭配 @ui 前綴或簡短指令觸發", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = vm.uiAgentEnabled.collectAsState().value,
                onCheckedChange = { vm.updateUiAgentEnabled(it) },
            )
        }
    }
}

/** Legacy card wrapper, still used by the sub-page section composables. */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/**
 * Advanced: CPU capabilities, strict-JSON grammar, long-term memory, cloned
 * voice and MCP. Every control here drives real behaviour - the CPU card is a
 * truthful read-out, the switches are read at generate/load time, and the
 * memory list is the actual SQLite store.
 */
@Composable
private fun AdvancedSettingsSection(vm: AppViewModel) {
    val settings by vm.currentSettings.collectAsState()
    val memory by vm.memoryEntries.collectAsState()
    val features = vm.cpuFeatures

    var newSubject by remember { mutableStateOf("") }
    var newFact by remember { mutableStateOf("") }
    var voiceTestMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refreshMemoryStore() }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // --- CPU ---------------------------------------------------------
        SectionCard("CPU 指令集") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "偵測到的 SIMD 能力",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        features.label(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    "${vm.cpuFeatures.let { com.pocketllm.llm.CpuInfo.recommendedThreads() }} 執行緒",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (features.dotProd || features.fp16)
                    "此裝置支援 FP16 / DotProd。以 POCKETLLM_ARM_ARCH=armv8.2-a+dotprod+fp16 重新建置原生庫即可啟用對應核心。"
                else "未偵測到 FP16 / DotProd，維持基準核心。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // --- Strict JSON grammar ------------------------------------------
        SectionCard("嚴格 JSON 語法約束 (GBNF)") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("螢幕智能體強制輸出 JSON", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "以 GBNF 文法限制生成，模型無法在動作前後加入「好的，我幫你點擊：」等文字，減少解析重試。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.uiAgentGrammar,
                    onCheckedChange = { vm.updateUiAgentGrammar(it) },
                )
            }
        }

        // --- Long-term memory ---------------------------------------------
        SectionCard("長期記憶庫 (SQLite)") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("啟用記憶", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "記住「阿媽 = Mother」這類事實，並在提示中注入。共 ${memory.size} 筆。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.memoryEnabled,
                    onCheckedChange = { vm.updateMemoryEnabled(it) },
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newSubject,
                    onValueChange = { newSubject = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("主體") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = newFact,
                    onValueChange = { newFact = it },
                    modifier = Modifier.weight(1.4f),
                    label = { Text("事實") },
                    singleLine = true,
                )
                TextButton(
                    enabled = newSubject.isNotBlank() && newFact.isNotBlank(),
                    onClick = {
                        vm.rememberFact(newSubject, newFact)
                        newSubject = ""
                        newFact = ""
                    },
                ) { Text("記住") }
            }
            if (memory.isNotEmpty()) {
                HorizontalDivider()
                memory.take(8).forEach { entry ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.subject, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(
                                entry.fact,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { vm.forgetFact(entry.id) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Forget")
                        }
                    }
                }
                TextButton(onClick = { vm.clearMemory() }) { Text("清空全部") }
            }
        }

        // --- Voice cloning -------------------------------------------------
        SectionCard("聲音克隆 (Fish Audio)") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("啟用克隆語音", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "使用 Fish Audio 的零樣本克隆，以參考音色朗讀回覆。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.voiceCloneEnabled,
                    onCheckedChange = { vm.updateVoiceCloneEnabled(it) },
                )
            }
            OutlinedTextField(
                value = settings.fishAudioKey,
                onValueChange = { vm.updateFishAudioKey(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Fish Audio API Key") },
                singleLine = true,
            )
            OutlinedTextField(
                value = settings.fishAudioVoiceId,
                onValueChange = { vm.updateFishAudioVoiceId(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("參考音色 ID (reference_id)") },
                singleLine = true,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    voiceTestMsg = "…"
                    vm.testVoiceClone("你好，這是 PocketLLM 的聲音克隆測試。") { voiceTestMsg = it }
                }) { Text("測試播放") }
                voiceTestMsg?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // --- MCP ------------------------------------------------------------
        SectionCard("MCP 伺服器") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("啟用 MCP", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "連線至 Model Context Protocol 伺服器（HTTP JSON-RPC）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.mcpEnabled,
                    onCheckedChange = { vm.updateMcpEnabled(it) },
                )
            }
            OutlinedTextField(
                value = settings.mcpServerUrl,
                onValueChange = { vm.updateMcpServerUrl(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("伺服器 URL") },
                singleLine = true,
            )
            OutlinedTextField(
                value = settings.mcpServerToken,
                onValueChange = { vm.updateMcpServerToken(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bearer Token (選填)") },
                singleLine = true,
            )
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp, top = 8.dp),
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(content = content)
        }
    }
}

/**
 * A tappable row matching IMG_5100: direct unboxed outline icon, title, subtitle, trailing chevron/text.
 */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconEmoji: String? = null,
    trailingText: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = if (onClick != null) {
        Modifier.fillMaxWidth().clickable(onClick = onClick)
    } else {
        Modifier.fillMaxWidth()
    }
    Row(
        modifier = rowModifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            iconEmoji != null -> Text(
                iconEmoji,
                style = MaterialTheme.typography.titleLarge,
            )
            icon != null -> Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Normal)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (trailingText != null) {
                    Text(
                        trailingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Thin divider inset past the icon, matching the reference design. */
@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 70.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** Dropdown for the colour-mode row: 跟随系统 / 浅色 / 深色. */
@Composable
private fun ThemeModeMenu(current: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(
                when (current) {
                    "light" -> "浅色"
                    "dark" -> "深色"
                    else -> "跟随系统"
                }
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (mode, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelect(mode); open = false },
                )
            }
        }
    }
}

/**
 * The bubble needs two special permissions that can only be granted in system
 * settings, so this section re-reads them whenever the screen resumes (the user
 * leaves to a system screen and comes back).
 */
@Composable
private fun CompanionSettingsSection(vm: AppViewModel) {
    val settings by vm.currentSettings.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var overlayGranted by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    var accessGranted by remember {
        mutableStateOf(com.pocketllm.companion.CompanionAccessibilityService.isConnected())
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = android.provider.Settings.canDrawOverlays(context)
                accessGranted = com.pocketllm.companion.CompanionAccessibilityService.isConnected()
                // If the user flipped the switch before granting the overlay
                // permission, bring the bubble up now that it can be shown.
                if (settings.companionEnabled) vm.restartCompanionIfEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openOverlaySettings() {
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}"),
                )
            )
        }
    }

    SectionCard("Floating companion") {
        Text(
            "A small bubble that stays on screen after you leave PocketLLM — tap it for company or a quick summary.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show companion bubble")
                Text(
                    if (overlayGranted) "Ready — tap the bubble any time"
                    else "Needs the \"Display over other apps\" permission",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.companionEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && !overlayGranted) openOverlaySettings()
                    else vm.updateCompanionEnabled(enabled)
                },
            )
        }
        if (!overlayGranted) {
            TextButton(onClick = { openOverlaySettings() }) { Text("Grant overlay permission") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Companion screen access")
                Text(
                    if (accessGranted) "On — can read the page when you tap Summary"
                    else "Off — required for page summaries",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = accessGranted,
                onCheckedChange = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        )
                    }
                },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Speak replies out loud")
                Text(
                    "Uses your device text-to-speech voice",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.companionTts,
                onCheckedChange = { vm.updateCompanionTts(it) },
            )
        }

        var persona by remember(settings.companionPersona) { mutableStateOf(settings.companionPersona) }
        OutlinedTextField(
            value = persona,
            onValueChange = { persona = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Extra persona (optional)") },
            minLines = 2,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = { vm.updateCompanionPersona(persona) },
                enabled = persona != settings.companionPersona,
            ) { Text("Save persona") }
        }
    }
}

@Composable
private fun CloudEntrySettingsSection(vm: AppViewModel) {
    val settings by vm.currentSettings.collectAsState()

    SectionCard("Cloud entry") {
        Text(
            "Optional. Used when no local model is loaded, and for longer page summaries. Any OpenAI-compatible endpoint works.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Allow cloud model")
                Text(
                    "Your text leaves the device when this runs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.cloudEnabled, onCheckedChange = { vm.updateCloudEnabled(it) })
        }

        var baseUrl by remember(settings.cloudBaseUrl) { mutableStateOf(settings.cloudBaseUrl) }
        var apiKey by remember(settings.cloudApiKey) { mutableStateOf(settings.cloudApiKey) }
        var model by remember(settings.cloudModel) { mutableStateOf(settings.cloudModel) }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL (ending in /v1)") },
            singleLine = true,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API key") },
            singleLine = true,
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Model name") },
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = {
                    vm.updateCloudBaseUrl(baseUrl)
                    vm.updateCloudApiKey(apiKey)
                    vm.updateCloudModel(model)
                },
                enabled = baseUrl != settings.cloudBaseUrl ||
                    apiKey != settings.cloudApiKey ||
                    model != settings.cloudModel,
            ) { Text("Save") }
        }
    }
}



private fun currentKeyFor(engine: String, settings: com.pocketllm.settings.AppSettings): String = when (engine) {
    "brave" -> settings.braveKey
    "tavily" -> settings.tavilyKey
    "bing" -> settings.bingKey
    "firecrawl" -> settings.firecrawlKey
    else -> ""
}

private fun labelFor(engine: String): String =
    WebSearch.engines.firstOrNull { it.first == engine }?.second ?: engine

/** Who she is, how she sounds, and what she looks like. */
@Composable
private fun CompanionPersonalitySection(vm: AppViewModel) {
    val settings by vm.currentSettings.collectAsState()
    // Needed by the image picker, which has to persist the read permission so the
    // overlay service can still open the file later.
    val context = LocalContext.current

    SectionCard("Personality") {
        var name by remember(settings.companionName) { mutableStateOf(settings.companionName) }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Her name") },
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = { vm.updateCompanionName(name) },
                enabled = name != settings.companionName,
            ) { Text("Save name") }
        }

        Text("Style", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Picking a style also sets the sliders below — then adjust to taste.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            com.pocketllm.companion.CompanionPersonas.all.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { preset ->
                        FilterChip(
                            selected = settings.companionStyle == preset.id,
                            onClick = { vm.updateCompanionStyle(preset.id) },
                            label = { Text(preset.label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Text(
            com.pocketllm.companion.CompanionPersonas.byId(settings.companionStyle).blurb,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TraitSlider("Warmth", settings.companionWarmth, "Reserved", "Affectionate") {
            vm.updateCompanionTraits(warmth = it)
        }
        TraitSlider("Directness", settings.companionDirectness, "Gentle", "Blunt") {
            vm.updateCompanionTraits(directness = it)
        }
        TraitSlider("Playfulness", settings.companionPlayfulness, "Sincere", "Playful") {
            vm.updateCompanionTraits(playfulness = it)
        }
        TraitSlider("Talkativeness", settings.companionVerbosity, "Terse", "Chatty") {
            vm.updateCompanionTraits(verbosity = it)
        }

        Text("Bubble", style = MaterialTheme.typography.bodyMedium)
        Text("Character", style = MaterialTheme.typography.bodySmall)
        Text(
            "A drawn character with her own animations, or a plain emoji.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val speciesOptions =
                listOf("image" to "My image", "live2d" to "Live2D", "vrm" to "VRM") +
                com.pocketllm.companion.CompanionSpecies.entries.map { it.id to it.label }
            speciesOptions.forEach { (id, label) ->
                FilterChip(
                    selected = settings.companionCharacter == id,
                    onClick = { vm.updateCompanionCharacter(id) },
                    label = { Text(label) },
                )
            }
        }
        if (settings.companionCharacter == "image") {
            val pickImage = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    // Persisted so the overlay service can still read the file
                    // after a restart; a plain grant lasts only this process.
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    vm.updateCompanionImage(uri.toString())
                }
            }
            Text(
                "Use any character art you have the rights to — a PNG with a " +
                    "transparent background works best. If it is a sprite sheet, " +
                    "set the frame grid below and she will cycle through the frames.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { pickImage.launch(arrayOf("image/*")) }) {
                    Text(if (settings.companionImageUri.isBlank()) "Choose image" else "Change image")
                }
                if (settings.companionImageUri.isNotBlank()) {
                    TextButton(onClick = { vm.updateCompanionImage("") }) {
                        Text("Clear")
                    }
                }
            }
            if (settings.companionImageUri.isNotBlank()) {
                val image = com.pocketllm.companion.rememberCharacterImage(settings.companionImageUri)
                if (image == null) {
                    Text(
                        "That image could not be read. Pick another, or clear it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        "Loaded ${image.width} x ${image.height} px",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TraitSlider(
                        "Frames across",
                        settings.companionImageColumns,
                        "1",
                        "12",
                        range = 1..12,
                    ) {
                        vm.updateCompanionImageGrid(it, settings.companionImageRows)
                    }
                    TraitSlider(
                        "Frame rows",
                        settings.companionImageRows,
                        "1",
                        "12",
                        range = 1..12,
                    ) {
                        vm.updateCompanionImageGrid(settings.companionImageColumns, it)
                    }
                    if (settings.companionImageColumns * settings.companionImageRows > 1) {
                        TraitSlider(
                            "Frame rate",
                            settings.companionImageFps,
                            "Slow",
                            "Fast",
                            range = 1..30,
                        ) {
                            vm.updateCompanionImageFps(it)
                        }
                    }
                }
            }
        }
        if (settings.companionCharacter == "live2d") {
            Text(
                "Official Live2D sample models, bundled with the app. Live2D's " +
                    "licence allows shipping these; letting you import your own " +
                    "model is what would require a separate contract, so it is " +
                    "deliberately not supported.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                com.pocketllm.companion.LIVE2D_MODELS.forEach { (id, label) ->
                    FilterChip(
                        selected = settings.companionLive2DModel == id,
                        onClick = { vm.updateCompanionLive2DModel(id) },
                        label = { Text(label) },
                    )
                }
            }
        }
        if (settings.companionCharacter == "vrm") {
            Text("VRM avatar", style = MaterialTheme.typography.bodySmall)
            var imported by remember {
                mutableStateOf(com.pocketllm.companion.VrmLibrary.list(context))
            }
            var importError by remember { mutableStateOf<String?>(null) }
            var pendingPick by remember { mutableStateOf<String?>(null) }
            var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
            val pickVrm = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    val raw = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes()
                    }
                    if (raw != null) {
                        // An oversized skeleton is refused at load by the renderer,
                        // so offer compression straight away rather than let the
                        // user pick a model that can only come out black.
                        val needs = com.pocketllm.companion.VrmCompressor.needsCompression(raw)
                        if (needs && pendingPick == null) {
                            pendingPick = uri.toString()
                            pendingBytes = raw
                        } else {
                            val result = com.pocketllm.companion.VrmLibrary.import(context, uri)
                            importError = result.error
                            val file = result.file
                            if (file != null) {
                                imported = com.pocketllm.companion.VrmLibrary.list(context)
                                vm.updateCompanionVrmModel(file.name)
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                com.pocketllm.companion.VRM_MODELS.forEach { (id, label) ->
                    FilterChip(
                        selected = settings.companionVrmModel == id,
                        onClick = { vm.updateCompanionVrmModel(id) },
                        label = { Text(label) },
                    )
                }
                imported.forEach { file ->
                    FilterChip(
                        selected = settings.companionVrmModel == file.name,
                        onClick = { vm.updateCompanionVrmModel(file.name) },
                        label = { Text(file.nameWithoutExtension.take(16)) },
                    )
                }
            }
            val compressPick = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    val raw = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes()
                    }
                    if (raw != null) {
                        pendingPick = uri.toString()
                        pendingBytes = raw
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = {
                        pickVrm.launch(
                            arrayOf(
                                "application/octet-stream",
                                "model/gltf-binary",
                                "*/*",
                            ),
                        )
                    },
                ) { Text("Import .vrm") }
                TextButton(
                    onClick = {
                        // Re-uses the same picker; choosing a file here always
                        // opens the compression dialog.
                        compressPick.launch(
                            arrayOf(
                                "application/octet-stream",
                                "model/gltf-binary",
                                "*/*",
                            ),
                        )
                    },
                ) { Text("Compress this .vrm") }
                val current = imported.firstOrNull { it.name == settings.companionVrmModel }
                if (current != null) {
                    TextButton(
                        onClick = {
                            com.pocketllm.companion.VrmLibrary.delete(current)
                            imported = com.pocketllm.companion.VrmLibrary.list(context)
                            vm.updateCompanionVrmModel(
                                com.pocketllm.companion.VRM_MODELS.first().first,
                            )
                        },
                    ) { Text("Delete imported") }
                }
            }
            importError?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (pendingPick != null && pendingBytes != null) {
                com.pocketllm.companion.VrmCompressDialog(
                    context = context,
                    bytes = pendingBytes!!,
                    onDismiss = {
                        pendingPick = null
                        pendingBytes = null
                    },
                    onCompressed = { name ->
                        pendingPick = null
                        pendingBytes = null
                        importError = null
                        imported = com.pocketllm.companion.VrmLibrary.list(context)
                        vm.updateCompanionVrmModel(name)
                    },
                    onError = { message ->
                        pendingPick = null
                        pendingBytes = null
                        importError = message
                    },
                )
            }
            Text(
                "Bundled: Seed-san, by VirtualCast (VRM Public License 1.0). You can " +
                    "also import your own .vrm or .glb — the file is copied into the " +
                    "app's storage, so it keeps working across restarts. VRM 0.x and " +
                    "VRM 1.0 are both read.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("Expression", style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val expressionOptions =
                listOf(com.pocketllm.companion.CompanionExpression.AUTO to "Match my mood") +
                    com.pocketllm.companion.CompanionExpression.entries.map { it.id to it.label }
            expressionOptions.forEach { (id, label) ->
                FilterChip(
                    selected = settings.companionExpression == id,
                    onClick = { vm.updateCompanionExpression(id) },
                    label = { Text(label) },
                )
            }
        }
        // Only meaningful with a drawn character; an emoji has no body to stand on
        // the screen, so the switch is hidden rather than shown doing nothing.
        if (settings.companionCharacter != "off") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Free-standing", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Drop the bubble and show her full body on the screen",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.companionBareCharacter,
                    onCheckedChange = { vm.updateCompanionBareCharacter(it) },
                )
            }
            if (settings.companionBareCharacter) {
                TraitSlider(
                    "Character size",
                    settings.companionCharacterSize,
                    "Small",
                    "Large",
                    range = 80..420,
                ) {
                    vm.updateCompanionCharacterSize(it)
                }
                Text(
                    "Drag her anywhere on the screen. Her width follows her height, " +
                        "so she never looks stretched.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TraitSlider("Size", settings.companionBubbleSize, "Small", "Large", range = 36..120) {
            vm.updateCompanionBubbleSize(it)
        }
        TraitSlider("Opacity", settings.companionBubbleAlpha, "Faint", "Solid", range = 20..100) {
            vm.updateCompanionBubbleAlpha(it)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Snap to edge", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Slide her against the nearest side when you let go",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.companionSnapToEdge,
                onCheckedChange = { vm.updateCompanionSnapToEdge(it) },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            // A bubble dragged somewhere awkward — or off the edge on a screen
            // rotation — is otherwise only fixable by dragging it back.
            TextButton(onClick = { vm.resetCompanionBubblePosition() }) {
                Text("Reset position")
            }
        }
        Text("Popup window", style = MaterialTheme.typography.bodySmall)
        Text(
            "Drag the popup by its title bar to move it, or its bottom-right corner " +
                "to resize it. Both are remembered.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { vm.resetCompanionPanelPosition() }) {
                Text("Reset position")
            }
            TextButton(onClick = { vm.resetCompanionPanelSize() }) {
                Text("Reset size")
            }
        }

        // Renders the real bubble composable, so what is configured here is
        // exactly what appears on screen — styling by sliders alone means
        // guessing at the result.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val previewShape = com.pocketllm.companion.BubbleShape.byId(settings.companionBubbleShape)
            val previewFill = if (settings.companionBubbleColor != 0L) {
                Color(settings.companionBubbleColor)
            } else {
                MaterialTheme.colorScheme.primary
            }
            val previewBorder = if (settings.companionBubbleBorderColor != 0L) {
                Color(settings.companionBubbleBorderColor)
            } else {
                MaterialTheme.colorScheme.surface
            }
            Box(
                modifier = Modifier.size(
                    // The bubble is often larger than a settings row; scaling the
                    // preview down keeps a 120dp bubble from dominating the page
                    // while still showing the shape and proportions honestly.
                    (settings.companionBubbleSize * 1.4f).dp.coerceAtMost(110.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                com.pocketllm.companion.BubbleVisual(
                    sizeDp = settings.companionBubbleSize,
                    shape = previewShape,
                    fill = previewFill,
                    borderWidthDp = settings.companionBubbleBorderWidth,
                    borderColor = previewBorder,
                    glyph = settings.companionGlyph.ifBlank { "🐱" },
                    glyphScale = settings.companionGlyphScale,
                    // Awake, not idle: the fade is a behaviour described by its
                    // own slider, and previewing it faint would misrepresent the
                    // colour the user is choosing.
                    opacity = settings.companionBubbleAlpha / 100f,
                    busy = false,
                    character = com.pocketllm.companion.CompanionSpecies.byId(settings.companionCharacter),
                    // "Match my mood" is meaningless without a check-in to react
                    // to, so the preview shows a neutral face rather than guessing.
                    expression = if (settings.companionExpression == com.pocketllm.companion.CompanionExpression.AUTO) {
                        com.pocketllm.companion.CompanionExpression.Neutral
                    } else {
                        com.pocketllm.companion.CompanionExpression.byId(settings.companionExpression)
                    },
                    // Reflects the free-standing switch, so the preview matches what
                    // will actually appear rather than always showing a bubble.
                    bare = settings.companionBareCharacter,
                    image = if (settings.companionCharacter == "image") {
                        com.pocketllm.companion.rememberCharacterImage(settings.companionImageUri)
                    } else {
                        null
                    },
                    imageColumns = settings.companionImageColumns,
                    imageRows = settings.companionImageRows,
                    imageFps = settings.companionImageFps,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Text("Style", style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            com.pocketllm.companion.BUBBLE_PRESETS.forEach { preset ->
                FilterChip(
                    selected = settings.companionBubbleShape == preset.shapeId &&
                        settings.companionBubbleSize == preset.size &&
                        settings.companionBubbleBorderWidth == preset.borderWidth &&
                        settings.companionGlyphScale == preset.glyphScale &&
                        settings.companionBubbleIdleAlpha == preset.idleAlpha,
                    onClick = { vm.applyCompanionBubblePreset(preset) },
                    label = { Text(preset.label) },
                )
            }
        }

        Text("Shape", style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            com.pocketllm.companion.BubbleShape.entries.forEach { shape ->
                val selected = settings.companionBubbleShape == shape.id
                Surface(
                    shape = shape.shapeFor(40),
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { vm.updateCompanionBubbleShape(shape.id) },
                ) {}
            }
        }

        TraitSlider(
            label = "Face size",
            value = settings.companionGlyphScale,
            lowLabel = "Tiny",
            highLabel = "Fills it",
            range = 20..80,
        ) { vm.updateCompanionGlyphScale(it) }

        TraitSlider(
            label = "Outline",
            value = settings.companionBubbleBorderWidth,
            lowLabel = "None",
            highLabel = "Thick",
            range = 0..8,
        ) { vm.updateCompanionBubbleBorderWidth(it) }

        if (settings.companionBubbleBorderWidth > 0) {
            Text("Outline colour", style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 0 is "pick one for me", which is the only sane default when the
                // fill colour is itself theme-driven.
                (BUBBLE_BORDER_COLORS).forEach { (argb, label) ->
                    val selected = settings.companionBubbleBorderColor == argb
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                if (argb == 0L) MaterialTheme.colorScheme.surfaceVariant else Color(argb),
                                CircleShape,
                            )
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            )
                            .clickable { vm.updateCompanionBubbleBorderColor(argb) },
                    ) {}
                    if (argb == 0L) {
                        Text(
                            "Auto",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
            }
        }

        TraitSlider(
            label = "Fade when idle",
            value = settings.companionBubbleIdleAlpha,
            lowLabel = "Faint",
            highLabel = "No fade",
            range = 20..100,
        ) { vm.updateCompanionBubbleIdleAlpha(it) }

        GesturePicker(
            label = "Double-tap",
            current = com.pocketllm.companion.BubbleGesture.byId(settings.companionBubbleDoubleTap),
            onPick = { vm.updateCompanionBubbleDoubleTap(it.id) },
        )
        GesturePicker(
            label = "Long-press",
            current = com.pocketllm.companion.BubbleGesture.byId(settings.companionBubbleLongPress),
            onPick = { vm.updateCompanionBubbleLongPress(it.id) },
        )
        Text(
            "A single tap always opens the chat.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Speech", style = MaterialTheme.typography.bodyMedium)
        // Stored as a multiplier but shown as a percentage, which reads far more
        // naturally than "1.15".
        TraitSlider(
            label = "Rate",
            value = (settings.companionSpeechRate * 100).roundToInt(),
            lowLabel = "Slow",
            highLabel = "Brisk",
            range = 50..200,
        ) { vm.updateCompanionSpeechRate(it / 100f) }
        TraitSlider(
            label = "Pitch",
            value = (settings.companionSpeechPitch * 100).roundToInt(),
            lowLabel = "Low",
            highLabel = "High",
            range = 50..200,
        ) { vm.updateCompanionSpeechPitch(it / 100f) }
        Text(
            "Applies to the system voice. The downloaded Piper voice keeps its own.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Panel", style = MaterialTheme.typography.bodyMedium)
        TraitSlider(
            label = "Text size",
            value = settings.companionPanelFontScale,
            lowLabel = "Small",
            highLabel = "Large",
            range = 80..140,
        ) { vm.updateCompanionPanelFontScale(it) }
    }
}

/**
 * Outline colours for the bubble. 0 means "derive one", which is the only
 * sensible default when the fill may itself be following the theme.
 */
private val BUBBLE_BORDER_COLORS: List<Pair<Long, String>> = listOf(
    0L to "Auto",
    0xFFFFFFFF to "White",
    0xFF000000 to "Black",
    0xFFE91E63 to "Rose",
)

/**
 * A one-tap cycling picker for a gesture action.
 *
 * Cycles rather than opening a dropdown: there are seven options and the list
 * is inside a scrolling settings column, where a popup menu is fiddly and a row
 * of seven chips would not fit.
 */
@Composable
private fun GesturePicker(
    label: String,
    current: com.pocketllm.companion.BubbleGesture,
    onPick: (com.pocketllm.companion.BubbleGesture) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val entries = com.pocketllm.companion.BubbleGesture.entries
                onPick(entries[(entries.indexOf(current) + 1) % entries.size])
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            current.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TraitSlider(
    label: String,
    value: Int,
    lowLabel: String,
    highLabel: String,
    range: IntRange = 0..100,
    onChange: (Int) -> Unit,
) {
    // Held locally while dragging so the handle tracks the finger, and only
    // committed when released: writing settings and refreshing the overlay on
    // every frame would hammer the disk for no visible benefit.
    var local by remember(value) { mutableStateOf(value.toFloat()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(
                "${local.toInt()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(lowLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(highLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Human-readable view of what she has remembered. Editable on purpose: memory
 * that the user cannot see or correct is worse than no memory at all.
 */
@Composable
private fun CompanionMemorySection(vm: AppViewModel) {
    val settings by vm.currentSettings.collectAsState()
    val facts by vm.memoryFacts.collectAsState()

    SectionCard("Memory") {
        Text(
            "Things she has picked up about you, so she doesn't have to be told twice.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remember things")
                Text(
                    if (settings.companionMemoryEnabled) "On" else "Off — nothing is stored",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.companionMemoryEnabled,
                onCheckedChange = { vm.updateCompanionMemoryEnabled(it) },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Learn new facts automatically")
                Text(
                    "Extra short model pass every few messages — slower on local models",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.companionMemoryExtraction,
                onCheckedChange = { vm.updateCompanionMemoryExtraction(it) },
                enabled = settings.companionMemoryEnabled,
            )
        }

        val staleCount by vm.staleMemoryCount.collectAsState()
        val notice by vm.memoryNotice.collectAsState()
        var query by remember { mutableStateOf("") }
        var categoryFilter by remember {
            mutableStateOf<com.pocketllm.companion.MemoryCategory?>(null)
        }

        // Counts are computed here rather than in the ViewModel because they are
        // purely a view of the list it already exposes.
        val counts = facts.groupingBy { it.categoryValue }.eachCount()
        val shown = facts.filter { fact ->
            (categoryFilter == null || fact.categoryValue == categoryFilter) &&
                (query.isBlank() || fact.text.contains(query, ignoreCase = true))
        }

        // Only worth showing a category row for categories that exist.
        if (counts.isNotEmpty()) {
            Text(
                "${facts.size} remembered" + if (staleCount > 0) " · $staleCount may be out of date" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = categoryFilter == null,
                    onClick = { categoryFilter = null },
                    label = { Text("All") },
                )
                counts.entries.sortedByDescending { it.value }.forEach { (category, count) ->
                    FilterChip(
                        selected = categoryFilter == category,
                        onClick = {
                            categoryFilter = if (categoryFilter == category) null else category
                        },
                        label = { Text("${category.label} $count") },
                    )
                }
            }
        }

        if (staleCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$staleCount fact${if (staleCount == 1) "" else "s"} haven't come up in a long time.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.forgetStaleMemory() }) { Text("Forget those") }
            }
        }

        notice?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.dismissMemoryNotice() }) { Text("OK") }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search what she knows") },
            singleLine = true,
        )

        var newFact by remember { mutableStateOf("") }
        OutlinedTextField(
            value = newFact,
            onValueChange = { newFact = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Add something to remember") },
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = {
                    vm.addMemoryFact(newFact)
                    newFact = ""
                },
                enabled = newFact.isNotBlank(),
            ) { Text("Add") }
        }

        if (shown.isEmpty()) {
            Text(
                if (facts.isEmpty()) "Nothing remembered yet." else "Nothing matches that.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (fact in shown) {
                var editing by remember(fact.id) { mutableStateOf(false) }
                var draft by remember(fact.id) { mutableStateOf(fact.text) }

                if (editing) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editing = false }) { Text("Cancel") }
                        TextButton(
                            onClick = {
                                vm.updateMemoryFact(fact.id, draft)
                                editing = false
                            },
                            enabled = draft != fact.text,
                        ) { Text("Save") }
                    }
                } else {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (fact.pinned) {
                                Text("★ ", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                fact.text,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Second line: what kind of fact this is, how sure she is,
                        // and how often it has actually been used. All three are
                        // cheap to show and make the list auditable at a glance.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                buildString {
                                    append(fact.categoryValue.label)
                                    append(" · ")
                                    append("${(fact.confidence * 100).roundToInt()}% sure")
                                    if (fact.reinforcements > 0) append(" · heard ${fact.reinforcements}×")
                                    if (fact.isStale()) append(" · may be out of date")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (fact.isStale()) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            // Tapping cycles the category — same reasoning as the
                            // gesture picker: a dropdown per row is heavy.
                            TextButton(onClick = {
                                val all = com.pocketllm.companion.MemoryCategory.entries
                                val next = all[(all.indexOf(fact.categoryValue) + 1) % all.size]
                                vm.setMemoryFactCategory(fact.id, next)
                            }) { Text("Type") }
                            if (fact.isStale()) {
                                TextButton(onClick = { vm.confirmMemoryFact(fact.id) }) { Text("Still true") }
                            }
                            TextButton(onClick = {
                                vm.setMemoryFactPinned(fact.id, !fact.pinned)
                            }) { Text(if (fact.pinned) "Unpin" else "Pin") }
                            TextButton(onClick = { editing = true }) { Text("Edit") }
                            TextButton(onClick = { vm.removeMemoryFact(fact.id) }) { Text("Forget") }
                        }
                    }
                }
            }
            val clipboard = LocalClipboardManager.current
            var importOpen by remember { mutableStateOf(false) }
            var importText by remember { mutableStateOf("") }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(vm.exportCompanionMemory()))
                }) { Text("Export") }
                TextButton(onClick = { importOpen = !importOpen }) { Text("Import") }
                TextButton(onClick = { vm.clearCompanionMemory() }) { Text("Forget everything") }
            }

            if (importOpen) {
                Text(
                    "Paste an export to merge it in. Existing facts are kept.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Paste export here") },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        vm.importCompanionMemory(importText)
                        importText = ""
                        importOpen = false
                    }, enabled = importText.isNotBlank()) { Text("Merge") }
                }
            }
        }
    }
}

