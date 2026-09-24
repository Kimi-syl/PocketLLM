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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pocketllm.AppViewModel
import com.pocketllm.util.WebSearch
import kotlin.math.roundToInt

/**
 * Settings are grouped by area instead of one long page, so the companion
 * controls are not buried under inference options.
 */
private enum class SettingsCategory(val label: String) {
    Model("Model"),
    Companion("Companion"),
    Voice("Voice"),
    Chat("Chat"),
    Server("Server"),
    About("About"),
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

    var _localInstallHint by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    var category by remember { mutableStateOf(SettingsCategory.Model) }
    var subPage by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    val backStack: MutableList<SettingsCategory?> = remember { mutableStateListOf<SettingsCategory?>() }
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
        }.getOrNull() ?: "?"
    }

    // A sub-page fills the screen with its original sections; the root page is
    // the large-title + grouped-rows layout from the reference design.
    if (subPage != null || showAbout) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    ScreenHeader(
                        when {
                            showAbout -> "About"
                            subPage == SettingsCategory.Companion -> "Companion"
                            subPage == SettingsCategory.Voice -> "Voice"
                            subPage == SettingsCategory.Chat -> "Chat"
                            else -> "Server"
                        },
                        onMenuClick = onMenu,
                        onBack = { subPage = null; showAbout = false },
                    )
                }
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
                    subPage == SettingsCategory.Model -> {
                        item { ModelSettingsSection(vm) }
                    }
                    subPage == SettingsCategory.Companion -> {
                        item { CompanionSettingsSection(vm) }
                        item { CompanionPersonalitySection(vm) }
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
                        item { ServerSettingsSections(vm, keys) }
                    }
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("Settings", onMenuClick = onMenu) }
        item {
            Text(
                "设置",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            SettingsGroup("通用设置") {
                SettingsRow(
                    title = "颜色模式",
                    subtitle = when (settings.themeMode) {
                        "light" -> "浅色"
                        "dark" -> "深色"
                        else -> "跟随系统"
                    },
                    icon = Icons.Outlined.WbSunny,
                    trailing = {
                        ThemeModeMenu(
                            current = settings.themeMode,
                            onSelect = { vm.updateThemeMode(it) },
                        )
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = "偏好设置",
                    subtitle = "主题、通知、界面和常规设置",
                    icon = Icons.Outlined.Settings,
                    onClick = { category = SettingsCategory.Companion; subPage = SettingsCategory.Companion; backStack.clear() },
                )
                SettingsDivider()
                SettingsRow(
                    title = "助手",
                    subtitle = "设置个性化助手 (智能体)",
                    icon = Icons.Outlined.SentimentSatisfied,
                    onClick = { category = SettingsCategory.Companion; subPage = SettingsCategory.Companion },
                )
                SettingsDivider()
                SettingsRow(
                    title = "扩展管理",
                    subtitle = "管理提示词注入、技能等扩展",
                    icon = Icons.Outlined.Extension,
                    onClick = { },
                )
            }
        }
        item {
            SettingsGroup("模型与服务") {
                SettingsRow(
                    title = "默认模型和提示词",
                    subtitle = "设置各个功能的默认模型",
                    icon = Icons.Outlined.AutoAwesome,
                    onClick = { category = SettingsCategory.Model; subPage = SettingsCategory.Model; },
                )
                SettingsDivider()
                SettingsRow(
                    title = "提供商",
                    subtitle = "配置 AI 提供商",
                    icon = Icons.Outlined.Psychology,
                    onClick = { },
                )
                SettingsDivider()
                SettingsRow(
                    title = "搜索服务",
                    subtitle = "设置搜索服务",
                    icon = Icons.Outlined.TravelExplore,
                    onClick = { subPage = SettingsCategory.Model },
                )
                SettingsDivider()
                SettingsRow(
                    title = "语音服务",
                    subtitle = "设置语音服务",
                    icon = Icons.Outlined.Mic,
                    onClick = { subPage = SettingsCategory.Voice },
                )
            }
        }
        item {
            SettingsGroup("应用") {
                SettingsRow(
                    title = "聊天",
                    subtitle = "聊天行为与输入",
                    icon = Icons.Outlined.Chat,
                    onClick = { subPage = SettingsCategory.Chat },
                )
                SettingsDivider()
                SettingsRow(
                    title = "服务器",
                    subtitle = "API 服务与密钥",
                    icon = Icons.Outlined.Public,
                    onClick = { subPage = SettingsCategory.Server },
                )
                SettingsDivider()
                SettingsRow(
                    title = "关于",
                    subtitle = "PocketLLM v$versionName",
                    icon = Icons.Outlined.Info,
                    onClick = { showAbout = true },
                )
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
        val gpuSupported = remember { com.pocketllm.llm.LlamaBridge.supportsGpuOffload() }
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
) {
    SectionCard("Web service") {
        Text("Server lifecycle lives in the Server tab.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { }) { Text("Open Server dashboard") }
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
        TextButton(onClick = { }) { Text("Manage keys") }
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

@Composable
private fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(content = content)
        }
    }
}

/**
 * A tappable row: icon in a soft circle, title, subtitle, trailing widget.
 * The single visual unit of the redesigned settings page.
 */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconEmoji: String? = null,
    trailing: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = if (onClick != null) {
        Modifier.fillMaxWidth().clickable(onClick = onClick)
    } else {
        Modifier.fillMaxWidth()
    }
    Row(
        modifier = rowModifier
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .padding(horizontal = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            iconEmoji != null -> Text(
                iconEmoji,
                style = MaterialTheme.typography.titleLarge,
            )
            icon != null -> Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
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
                listOf("off" to "Emoji", "image" to "My image", "live2d" to "Live2D", "vrm" to "VRM") +
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
        // Only offered when there is no character to draw instead; showing emoji
        // chips beside a cat that ignores them is just confusing.
        if (settings.companionCharacter == "off") {
            var glyph by remember(settings.companionGlyph) { mutableStateOf(settings.companionGlyph) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                com.pocketllm.companion.BUBBLE_GLYPH_CHOICES.forEach { face ->
                    Surface(
                        shape = CircleShape,
                        color = if (settings.companionGlyph == face) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .size(36.dp)
                            .clickable {
                                glyph = face
                                // Applied immediately: picking a face is the whole
                                // gesture, so a separate Save step is just a chore.
                                vm.updateCompanionGlyph(face)
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(face, fontSize = 17.sp)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = glyph,
                onValueChange = { glyph = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bubble face (any emoji)") },
                singleLine = true,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { vm.updateCompanionGlyph(glyph) },
                    enabled = glyph != settings.companionGlyph,
                ) { Text("Save face") }
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

