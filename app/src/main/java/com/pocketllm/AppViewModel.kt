package com.pocketllm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketllm.hf.HfSearchResult
import com.pocketllm.hf.HfTreeEntry
import com.pocketllm.hf.HuggingFaceClient
import com.pocketllm.keys.ApiKeyEntry
import com.pocketllm.keys.ApiKeyRepository
import com.pocketllm.llm.CpuInfo
import com.pocketllm.llm.EngineState
import com.pocketllm.llm.GenParams
import com.pocketllm.llm.LlamaEngine
import com.pocketllm.models.GgufModel
import com.pocketllm.models.ModelRepository
import com.pocketllm.server.ApiServer
import com.pocketllm.server.ServerLog
import com.pocketllm.sessions.ChatSession
import com.pocketllm.sessions.ChatSessionRepository
import com.pocketllm.sessions.SessionMessage
import com.pocketllm.settings.AppSettings
import com.pocketllm.settings.SettingsRepository
import com.pocketllm.util.TtsManager
import com.pocketllm.util.WebSearch
import com.pocketllm.usage.UsageRecord
import com.pocketllm.usage.UsageRepository
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiMessage(
    val role: String,
    val content: String,
    val toolCalls: List<com.pocketllm.agent.ToolCallUi> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0,
    val timeToFirstTokenMs: Long? = null,
    val tokensPerSecond: Float? = null,
    val totalDurationMs: Long = 0,
)

private data class ChatMetrics(
    val promptTokens: Int,
    val generatedTokens: Int,
    val ttftMs: Long?,
    val tokensPerSecond: Float,
    val totalDurationMs: Long,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Application get() = getApplication()

    val engine = LlamaEngine
    private val settings = SettingsRepository(context)
    private val apiKeyRepo = ApiKeyRepository(context)
    private val hfClient = HuggingFaceClient { settings.current().hfToken }
    private val modelRepo = ModelRepository(context) { settings.current().hfToken }
    private val usageRepo = UsageRepository(context)
    private val tlsInfo = MutableStateFlow<String?>(null)
    val tlsFingerprint: StateFlow<String?> = tlsInfo

    private val tts = TtsManager(context)
    private val sessionRepo = ChatSessionRepository(context)

    val ttsReady: StateFlow<Boolean> = tts.ready
    val ttsSpeaking: StateFlow<Boolean> = tts.speaking
    val piperReady: StateFlow<Boolean> = tts.piperReady
    val piperProgress: StateFlow<Float> = tts.piperProgress
    val piperStatus: StateFlow<String> = tts.piperStatus
    val piperState: StateFlow<com.pocketllm.util.SherpaTtsEngine.State> = tts.piperState
    val ttsEngine: StateFlow<String> = tts.activeEngine

    val server = ApiServer(context, settings, apiKeyRepo, usageRepo) { refreshUsage() }

    fun speakOrStop(text: String) {
        if (tts.speaking.value) tts.stop() else tts.speak(text)
    }

    fun speakText(text: String) {
        tts.speak(text)
    }

    

    private val _models = MutableStateFlow<List<GgufModel>>(emptyList())
    val models: StateFlow<List<GgufModel>> = _models

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress

    private val _searchResults = MutableStateFlow<List<HfSearchResult>>(emptyList())
    val searchResults: StateFlow<List<HfSearchResult>> = _searchResults

    private val _searchLoading = MutableStateFlow(false)
    val searchLoading: StateFlow<Boolean> = _searchLoading

    private val _fileListing = MutableStateFlow<Pair<String, List<HfTreeEntry>>?>(null)
    val fileListing: StateFlow<Pair<String, List<HfTreeEntry>>?> = _fileListing

    private val _filesLoading = MutableStateFlow(false)
    val filesLoading: StateFlow<Boolean> = _filesLoading

    private val downloadCancelled = AtomicBoolean(false)

    private val _apiKeys = MutableStateFlow<List<ApiKeyEntry>>(emptyList())
    val apiKeys: StateFlow<List<ApiKeyEntry>> = _apiKeys

    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning

    private val _currentSettings = MutableStateFlow(AppSettings())
    val currentSettings: StateFlow<AppSettings> = _currentSettings

    private val _chatMessages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatUiMessage>> = _chatMessages

    private val _agentStatus = MutableStateFlow<String?>(null)
    val agentStatus: StateFlow<String?> = _agentStatus

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating

    private val _webSearchEnabled = MutableStateFlow(false)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled

    fun toggleWebSearch() {
        _webSearchEnabled.value = !_webSearchEnabled.value
    }

    // --- Attachment state ---
    private val _attachment = MutableStateFlow<AttachedFile?>(null)
    val attachment: StateFlow<AttachedFile?> = _attachment

    /**
     * Open the system file picker. The Activity should launch
     * [android.content.Intent.ACTION_OPEN_DOCUMENT] and pass the result URI
     * to [onFilePicked].
     */
    fun openFilePicker() {
        // The actual launch happens in MainActivity; this is just a hook so
        // the ViewModel can show the "pick" intent. The Activity observes
        // [pickFileRequest] StateFlow.
        _pickFileRequest.value = System.currentTimeMillis()
    }
    private val _pickFileRequest = MutableStateFlow(0L)
    val pickFileRequest: StateFlow<Long> = _pickFileRequest

    fun onFilePicked(uri: android.net.Uri) {
        viewModelScope.launch {
            val attached = withContext(Dispatchers.IO) {
                runCatching { AttachedFile.fromUri(context, uri) }.getOrNull()
            }
            if (attached != null) {
                _attachment.value = attached
            }
        }
    }

    fun clearAttachment() {
        _attachment.value = null
    }

    private val _agentEnabled = MutableStateFlow(false)
    val agentEnabled: StateFlow<Boolean> = _agentEnabled

    fun toggleAgent() {
        _agentEnabled.value = !_agentEnabled.value
    }

    private val sandboxDir: java.io.File = java.io.File(context.filesDir, "sandbox").also { it.mkdirs() }
    private val readFileTool = com.pocketllm.agent.ReadFileTool(sandboxDir).also { it.setContext(context) }
    private val writeFileTool = com.pocketllm.agent.WriteFileTool(sandboxDir)
    private val runCodeTool = com.pocketllm.agent.RunCodeTool(sandboxDir)
    private val clipboardTool = com.pocketllm.agent.ClipboardReadTool(context)
    private val deviceInfoTool = com.pocketllm.agent.DeviceInfoTool(context)
    private val webSearchTool = com.pocketllm.agent.WebSearchTool { _currentSettings.value }
    private val calculateTool = com.pocketllm.agent.CalculateTool()
    private val dateTimeTool = com.pocketllm.agent.DateTimeTool()
    private val webFetchTool = com.pocketllm.agent.WebFetchTool { query ->
        val snap = _currentSettings.value
        val key = when (snap.searchEngine) {
            "brave" -> snap.braveKey; "tavily" -> snap.tavilyKey
            "bing" -> snap.bingKey; "firecrawl" -> snap.firecrawlKey; else -> ""
        }
        com.pocketllm.util.WebSearch.search(query, snap.searchEngine, key.ifBlank { null }, 1)
            .firstOrNull()?.url
    }

    private val agentRegistry = com.pocketllm.agent.ToolRegistry().apply {
        register(webSearchTool)
        register(webFetchTool)
        register(calculateTool)
        register(dateTimeTool)
        register(readFileTool)
        register(writeFileTool)
        register(runCodeTool)
        register(clipboardTool)
        register(deviceInfoTool)
    }

    /** All tools in registration order — used by the long-press picker UI. */
    val allTools: List<com.pocketllm.agent.AgentTool> = agentRegistry.all()
    private val enabledToolsFlow: kotlinx.coroutines.flow.MutableStateFlow<Set<String>> =
        MutableStateFlow(_currentSettings.value.enabledTools)
    val enabledTools: kotlinx.coroutines.flow.StateFlow<Set<String>> = enabledToolsFlow

    fun setToolEnabled(name: String, enabled: Boolean) {
        val current = enabledToolsFlow.value.toMutableSet()
        if (enabled) current.add(name) else current.remove(name)
        enabledToolsFlow.value = current
        updateSettings { it.copy(enabledTools = current) }
    }

    private val agentLoop = com.pocketllm.agent.AgentLoop(
        engine = engine,
        registry = agentRegistry,
        enabledTools = { enabledToolsFlow.value },
    )

    private val _usageRecords = MutableStateFlow<List<UsageRecord>>(emptyList())
    val usageRecords: StateFlow<List<UsageRecord>> = _usageRecords

    // --- Session state ---
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions

    /** Live view of the in-memory log for the Logs screen. */
    val logLines: StateFlow<List<String>> = com.pocketllm.server.ServerLog.lines

    /** Force a re-read of the log file from disk. */
    fun refreshLog() {
        // Touch the StateFlow to force UI to re-collect; ServerLog.lines is
        // already a StateFlow that auto-updates when new lines are appended.
        // This is a no-op placeholder for explicit "refresh" UI affordance.
    }
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId

    init {
        // Initialize the log file sink before anything else so crashes
        // during init are captured.
        com.pocketllm.server.ServerLog.init(context)
        installUncaughtExceptionHandler()
        refreshModels()
        refreshKeys()
        refreshUsage()
        _currentSettings.value = settings.current()
        tlsInfo.value = com.pocketllm.util.TlsCertManager.readFingerprint(context.filesDir)
        tts.setEngine(settings.current().ttsEngine)
        _agentEnabled.value = settings.current().agentEnabled
        viewModelScope.launch { startNewSession() }
    }

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                com.pocketllm.server.ServerLog.error(
                    "UNCAUGHT on ${thread.name}",
                    throwable,
                )
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun exportLogToDownloads(): String? {
        val path = com.pocketllm.server.ServerLog.exportToDownloads(context)
        com.pocketllm.server.ServerLog.log("Log exported to: $path")
        return path
    }

    fun fullLogText(): String = com.pocketllm.server.ServerLog.lines.value.joinToString("\n")

    fun refreshSessions() {
        viewModelScope.launch { _sessions.value = sessionRepo.list() }
    }

    fun startNewSession() {
        viewModelScope.launch {
            persistCurrentSession()
            val s = sessionRepo.newSession()
            _currentSessionId.value = s.id
            _chatMessages.value = emptyList()
            _sessions.value = sessionRepo.list()
        }
    }

    fun loadSession(id: String) {
        viewModelScope.launch {
            persistCurrentSession()
            val s = sessionRepo.get(id) ?: return@launch
            _currentSessionId.value = s.id
            _chatMessages.value = s.messages.map { m ->
                ChatUiMessage(
                    role = m.role,
                    content = m.content,
                    toolCalls = m.toolCalls.map { tc ->
                        com.pocketllm.agent.ToolCallUi(
                            id = tc.id,
                            name = tc.name,
                            displayName = tc.displayName,
                            arguments = tc.arguments,
                            resultSummary = tc.resultSummary,
                            resultDetail = tc.resultDetail,
                        )
                    },
                    timestamp = m.timestamp,
                    promptTokens = m.promptTokens,
                    generatedTokens = m.generatedTokens,
                    timeToFirstTokenMs = m.timeToFirstTokenMs,
                    tokensPerSecond = m.tokensPerSecond,
                    totalDurationMs = m.totalDurationMs,
                )
            }
            _sessions.value = sessionRepo.list()
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            sessionRepo.delete(id)
            if (_currentSessionId.value == id) {
                startNewSession()
            } else {
                _sessions.value = sessionRepo.list()
            }
        }
    }

    private suspend fun persistCurrentSession() {
        val id = _currentSessionId.value ?: return
        val title = _chatMessages.value
            .firstOrNull { it.role == "user" }?.content?.lineSequence()?.firstOrNull()
            ?: "New chat"
        val messages = _chatMessages.value.map { m ->
            SessionMessage(
                role = m.role,
                content = m.content,
                timestamp = m.timestamp,
                promptTokens = m.promptTokens,
                generatedTokens = m.generatedTokens,
                timeToFirstTokenMs = m.timeToFirstTokenMs,
                tokensPerSecond = m.tokensPerSecond,
                totalDurationMs = m.totalDurationMs,
                toolCalls = m.toolCalls.map { com.pocketllm.sessions.PersistedToolCall.fromUi(it) },
            )
        }
        sessionRepo.save(ChatSession(id = id, title = title, messages = messages))
        _sessions.value = sessionRepo.list()
    }

    fun refreshUsage() {
        viewModelScope.launch { _usageRecords.value = usageRepo.list() }
    }

    fun clearUsage() {
        viewModelScope.launch {
            usageRepo.clear()
            refreshUsage()
        }
    }

    fun refreshModels() {
        _models.value = modelRepo.list()
    }

    fun searchHuggingFace(query: String) {
        if (query.isBlank() || _searchLoading.value) return
        viewModelScope.launch {
            _searchLoading.value = true
            runCatching { hfClient.search(query.trim()) }
                .onSuccess { _searchResults.value = it }
                .onFailure { ServerLog.log("HF search failed: ${it.message}") }
            _searchLoading.value = false
        }
    }

    fun loadRepoFiles(repoId: String) {
        if (_filesLoading.value) return
        viewModelScope.launch {
            _filesLoading.value = true
            runCatching { repoId to hfClient.listGgufFiles(repoId) }
                .onSuccess { _fileListing.value = it }
                .onFailure { ServerLog.log("File listing failed: ${it.message}") }
            _filesLoading.value = false
        }
    }

    fun clearRepoFiles() {
        _fileListing.value = null
    }

    fun downloadModel(url: String) {
        if (_downloadProgress.value != null) return
        downloadCancelled.set(false)
        viewModelScope.launch {
            _downloadProgress.value = 0f
            modelRepo.download(url.trim(), downloadCancelled) { progress -> _downloadProgress.value = progress }
                .onSuccess { ServerLog.log("Downloaded ${it.name}") }
                .onFailure { ServerLog.log("Download failed: ${it.message}") }
            _downloadProgress.value = null
            refreshModels()
        }
    }

    fun cancelDownload() {
        downloadCancelled.set(true)
    }

    fun loadModel(name: String) {
        val file = modelRepo.file(name) ?: return
        viewModelScope.launch {
            engine.load(file, settings.current().contextSize, CpuInfo.recommendedThreads(), settings.current().gpuOffload)
            refreshModels()
        }
    }

    fun unloadModel() {
        if (server.isRunning) stopServer()
        viewModelScope.launch {
            engine.unload()
            refreshModels()
        }
    }

    fun deleteModel(name: String) {
        modelRepo.delete(name)
        refreshModels()
    }

    fun loadedFileName(): String? {
        val ready = engine.state.value as? EngineState.Ready ?: return null
        return _models.value.firstOrNull { it.name.removeSuffix(".gguf") == ready.modelName }?.name
    }

    fun startServer() {
        server.start()
        _serverRunning.value = server.isRunning
    }

    fun stopServer() {
        server.stop()
        _serverRunning.value = false
    }

    fun refreshKeys() {
        viewModelScope.launch { _apiKeys.value = apiKeyRepo.list() }
    }

    fun createKey(name: String) {
        viewModelScope.launch {
            apiKeyRepo.create(name)
            refreshKeys()
        }
    }

    fun setKeyEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            apiKeyRepo.setEnabled(id, enabled)
            refreshKeys()
        }
    }

    fun deleteKey(id: String) {
        viewModelScope.launch {
            apiKeyRepo.delete(id)
            refreshKeys()
        }
    }

    fun updatePort(portText: String) {
        val port = portText.toIntOrNull()?.coerceIn(1024, 65535) ?: return
        updateSettings { it.copy(port = port) }
    }

    fun updateRequireApiKey(required: Boolean) {
        updateSettings { it.copy(requireApiKey = required) }
    }

    fun updateContextSize(sizeText: String) {
        val size = sizeText.toIntOrNull()?.coerceIn(256, 32768) ?: return
        updateSettings { it.copy(contextSize = size) }
    }

    fun updateHfToken(token: String) {
        updateSettings { it.copy(hfToken = token.trim()) }
    }

    fun updateThemeMode(mode: String) {
        updateSettings { it.copy(themeMode = mode) }
    }

    fun updateDynamicColor(enabled: Boolean) {
        updateSettings { it.copy(dynamicColor = enabled) }
    }

    fun updateStartupPrompt(prompt: String) {
        updateSettings { it.copy(startupPrompt = prompt) }
    }

    fun updateHttps(enabled: Boolean) {
        viewModelScope.launch {
            settings.update { it.copy(httpsEnabled = enabled) }
            _currentSettings.value = settings.current()
            if (server.isRunning) {
                server.stop()
                server.start()
                _serverRunning.value = server.isRunning
            }
            if (!enabled) {
                com.pocketllm.util.TlsCertManager.deleteTlsFiles(context.filesDir)
                tlsInfo.value = null
            } else {
                tlsInfo.value = com.pocketllm.util.TlsCertManager.readFingerprint(context.filesDir)
            }
        }
    }

    fun updateSearchEngine(engine: String) {
        updateSettings { it.copy(searchEngine = engine) }
    }

    fun updateEngineKey(engine: String, key: String) {
        updateSettings { current ->
            when (engine) {
                "brave" -> current.copy(braveKey = key.trim())
                "tavily" -> current.copy(tavilyKey = key.trim())
                "bing" -> current.copy(bingKey = key.trim())
                "firecrawl" -> current.copy(firecrawlKey = key.trim())
                else -> current
            }
        }
    }

    fun updateTtsAutoSpeak(enabled: Boolean) {
        updateSettings { it.copy(ttsAutoSpeak = enabled) }
    }

    fun updateAgentEnabled(enabled: Boolean) {
        updateSettings { it.copy(agentEnabled = enabled) }
        _agentEnabled.value = enabled
    }

    fun updateTtsEngine(engine: String) {
        updateSettings { it.copy(ttsEngine = engine) }
        tts.setEngine(engine)
        if (engine == "piper") {
            viewModelScope.launch { tts.ensurePiper() }
        }
    }

    fun retryPiperDownload() {
        viewModelScope.launch { tts.retryPiper() }
    }

    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage

    fun exportCertificate() {
        viewModelScope.launch {
            com.pocketllm.util.TlsCertManager.exportCertificateToDownloads(context)
                .onSuccess { msg ->
                    _exportMessage.value = msg
                    ServerLog.log(msg)
                }
                .onFailure { _exportMessage.value = "Export failed: ${it.message}" }
        }
    }

    fun installCertificateOnDevice(): Boolean {
        val intent = com.pocketllm.util.TlsCertManager.createInstallIntent(context) ?: return false
        runCatching {
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { return false }
        return true
    }

    fun updateGpuOffload(enabled: Boolean) {
        updateSettings { it.copy(gpuOffload = enabled) }
    }

    fun stopSpeaking() = tts.stop()

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settings.update(transform)
            _currentSettings.value = settings.current()
        }
    }

    fun localAddresses(): List<String> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .mapNotNull { it.hostAddress }
                .toList()
        }.getOrDefault(emptyList())

    fun sendChat(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _generating.value) return
        com.pocketllm.server.ServerLog.log("sendChat: text='${trimmed.take(80)}' agent=${_agentEnabled.value} model=${engine.state.value.javaClass.simpleName}")
        try {
            doSendChat(trimmed)
        } catch (e: Exception) {
            com.pocketllm.server.ServerLog.error("sendChat", e)
            // Best-effort: clear generating flag and status
            _generating.value = false
            _agentStatus.value = null
            // Show a visible error in the chat so the user knows something went wrong.
            _chatMessages.value = _chatMessages.value + ChatUiMessage(
                "assistant",
                "⚠️ sendChat crashed: ${e.message ?: e.javaClass.simpleName}\n\nCheck the Logs tab for the full stack trace.",
                timestamp = System.currentTimeMillis(),
            )
        }
    }

    private fun doSendChat(trimmed: String) {
        val attachment = _attachment.value
        val enriched = if (attachment != null) {
            val prefix = if (attachment.isText && attachment.textPreview != null) {
                "[Attached file: ${attachment.displayName} (${attachment.sizeBytes} bytes)]\n```\n${attachment.textPreview}\n```\n\n"
            } else {
                "[Attached file: ${attachment.displayName} (${attachment.sizeBytes} bytes, ${attachment.mimeType}). Saved at sandbox path: ${attachment.localFile.name}]\n\n"
            }
            prefix + trimmed
        } else trimmed
        // Consume the attachment once it's been sent.
        _attachment.value = null
        if (_agentEnabled.value) {
            sendChatWithAgent(enriched)
        } else {
            sendChatPlain(enriched)
        }
    }

    private fun sendChatPlain(trimmed: String) {
        viewModelScope.launch {
            com.pocketllm.server.ServerLog.log("sendChatPlain: starting")
            _generating.value = true
            try {
                val systemPrompt = _currentSettings.value.startupPrompt.trim()
                val userTs = System.currentTimeMillis()
                val visibleHistory = _chatMessages.value + ChatUiMessage("user", trimmed, timestamp = userTs)
                _chatMessages.value = visibleHistory + ChatUiMessage("assistant", "", timestamp = System.currentTimeMillis())
                val replyIndex = _chatMessages.value.lastIndex

                fun setReply(text: String, metrics: ChatMetrics? = null) {
                    _chatMessages.update { list ->
                        list.toMutableList().also {
                            it[replyIndex] = it[replyIndex].copy(
                                content = text,
                                promptTokens = metrics?.promptTokens ?: it[replyIndex].promptTokens,
                                generatedTokens = metrics?.generatedTokens ?: it[replyIndex].generatedTokens,
                                timeToFirstTokenMs = metrics?.ttftMs ?: it[replyIndex].timeToFirstTokenMs,
                                tokensPerSecond = metrics?.tokensPerSecond ?: it[replyIndex].tokensPerSecond,
                                totalDurationMs = metrics?.totalDurationMs ?: it[replyIndex].totalDurationMs,
                            )
                        }
                    }
                }

                var webContext: String? = null
                if (_webSearchEnabled.value) {
                    setReply("\uD83D\uDD0E Searching the web…")
                    val snap = _currentSettings.value
                    val keyForEngine = when (snap.searchEngine) {
                        "brave" -> snap.braveKey; "tavily" -> snap.tavilyKey
                        "bing" -> snap.bingKey; "firecrawl" -> snap.firecrawlKey; else -> ""
                    }
                    val results = WebSearch.search(trimmed, snap.searchEngine, keyForEngine.ifBlank { null })
                    webContext = if (results.isEmpty()) null else buildString {
                        appendLine("[Web search results for \"${trimmed}\":")
                        results.forEachIndexed { i, r ->
                            appendLine("${i + 1}. ${r.title} - ${r.snippet} (${r.url})")
                        }
                        appendLine("Use the above web search results to answer the user's question. Do not say you cannot access the internet.]")
                    }
                    ServerLog.log("Web search: ${results.size} results for \"${trimmed}\"")
                    setReply("")
                }

                val userMessage = if (webContext != null) {
                    "$webContext\n\nUser question: $trimmed"
                } else {
                    trimmed
                }

                val promptMessages = buildList {
                    if (systemPrompt.isNotEmpty()) add("system" to systemPrompt)
                    addAll(visibleHistory.dropLast(1).map { it.role to it.content })
                    add("user" to userMessage)
                }
                val prompt = engine.chatPrompt(promptMessages)
                if (prompt == null) {
                    _chatMessages.update { list ->
                        list.toMutableList().also { it[replyIndex] = ChatUiMessage("assistant", "[no model loaded]") }
                    }
                    return@launch
                }
                val modelName = (engine.state.value as? EngineState.Ready)?.modelName ?: "unknown"
                val genStart = System.nanoTime()
                var firstTokenNs: Long? = null
                val result = engine.generate(prompt, GenParams(maxTokens = 512)) { token ->
                    if (firstTokenNs == null) firstTokenNs = System.nanoTime()
                    _chatMessages.update { list ->
                        list.toMutableList().also {
                            it[replyIndex] = it[replyIndex].copy(content = it[replyIndex].content + token)
                        }
                    }
                }
                val genEnd = System.nanoTime()
                result?.let { r ->
                    val totalMs = (genEnd - genStart) / 1_000_000
                    val ttftMs = firstTokenNs?.let { (it - genStart) / 1_000_000 }
                    val tps = if (totalMs > 0) r.generatedTokens * 1000f / totalMs else 0f
                    val metrics = ChatMetrics(r.promptTokens, r.generatedTokens, ttftMs, tps, totalMs)
                    setReply(_chatMessages.value[replyIndex].content, metrics)
                    usageRepo.add(UsageRecord(System.currentTimeMillis(), "chat", modelName, r.promptTokens, r.generatedTokens))
                    refreshUsage()
                    persistCurrentSession()
                    if (_currentSettings.value.ttsAutoSpeak && !tts.speaking.value) {
                        val full = _chatMessages.value.getOrNull(replyIndex)?.content.orEmpty()
                        if (full.isNotBlank() && !full.startsWith("…")) {
                            tts.speak(full)
                        }
                    }
                }
            } finally {
                _generating.value = false
            }
        }
    }

    private fun sendChatWithAgent(trimmed: String) {
        viewModelScope.launch {
            com.pocketllm.server.ServerLog.log("sendChatWithAgent: starting, tools=${com.pocketllm.agent.AgentTool::class.simpleName} engineState=${engine.state.value.javaClass.simpleName}")
            _generating.value = true
            try {
                val systemPrompt = _currentSettings.value.startupPrompt.trim()
                val userTs = System.currentTimeMillis()
                // Use a stable identity token instead of an index. Indices can go
                // stale if the list is modified concurrently (e.g. by a parallel
                // sendChat, session switch, or clearChat). The token is unique
                // enough to identify the assistant placeholder across updates.
                val assistantToken = System.nanoTime()
                _chatMessages.value = _chatMessages.value + ChatUiMessage("user", trimmed, timestamp = userTs)
                val assistantMsg = ChatUiMessage(
                    "assistant", "", timestamp = System.currentTimeMillis(),
                    // Encode the token in a field we already have - reuse
                    // generatedTokens as a non-displayed token holder. Ugly but
                    // safe: we never display it.
                    generatedTokens = (assistantToken and 0x7FFFFFFF).toInt(),
                )
                _chatMessages.value = _chatMessages.value + assistantMsg

                val turn = mutableListOf<com.pocketllm.agent.ToolCallUi>()
                val genStart = System.nanoTime()
                var firstTokenNs: Long? = null
                var totalTokens = 0

                fun setReplyText(text: String) {
                    if (firstTokenNs == null && text.isNotEmpty()) firstTokenNs = System.nanoTime()
                    totalTokens += text.length
                    _chatMessages.update { list ->
                        list.toMutableList().also {
                            val idx = it.indexOfLast { m -> m.generatedTokens == assistantMsg.generatedTokens }
                            if (idx >= 0) {
                                it[idx] = it[idx].copy(content = text, toolCalls = turn.toList())
                            }
                        }
                    }
                }

                fun appendToolCall(ui: com.pocketllm.agent.ToolCallUi) {
                    turn += ui
                    _chatMessages.update { list ->
                        list.toMutableList().also {
                            val idx = it.indexOfLast { m -> m.generatedTokens == assistantMsg.generatedTokens }
                            if (idx >= 0) {
                                it[idx] = it[idx].copy(toolCalls = turn.toList())
                            }
                        }
                    }
                }

                val history = _chatMessages.value
                    .filter { it.timestamp != assistantMsg.timestamp || it.generatedTokens != assistantMsg.generatedTokens }
                    .map { it.role to it.content }
                val outcome = try {
                    agentLoop.run(
                        systemPrompt = systemPrompt,
                        history = history,
                        userMessage = trimmed,
                        onPartialReply = { text -> setReplyText(text) },
                        onToolInvoked = { ui -> appendToolCall(ui) },
                        onStatus = { status ->
                            _agentStatus.value = when (status) {
                                is com.pocketllm.agent.AgentLoop.Status.Thinking -> "Thinking\u2026"
                                is com.pocketllm.agent.AgentLoop.Status.RunningTool -> "Running ${status.tool}\u2026"
                            }
                        },
                    )
                } catch (e: Exception) {
                    ServerLog.log("Agent loop crashed: ${e.message}\n${e.stackTraceToString().take(500)}")
                    com.pocketllm.agent.AgentLoop.Outcome(
                        finalText = "Error: ${e.message ?: e.javaClass.simpleName}",
                        toolCalls = emptyList(),
                    )
                }

                val genEnd = System.nanoTime()
                val finalText = outcome.finalText.orEmpty()
                val ttftMs = firstTokenNs?.let { (it - genStart) / 1_000_000 }
                val totalMs = (genEnd - genStart) / 1_000_000
                val tps = if (totalMs > 0) totalTokens.toFloat() / totalMs * 1000f / 4f else 0f

                _chatMessages.update { list ->
                    list.toMutableList().also {
                        val idx = it.indexOfLast { m -> m.generatedTokens == assistantMsg.generatedTokens }
                        if (idx >= 0) {
                            it[idx] = it[idx].copy(
                                content = if (finalText.isBlank() && it[idx].content.isBlank()) "(no response)" else finalText.ifBlank { it[idx].content },
                                toolCalls = turn.toList(),
                                generatedTokens = totalTokens / 4,
                                timeToFirstTokenMs = ttftMs,
                                tokensPerSecond = tps,
                                totalDurationMs = totalMs,
                            )
                        }
                    }
                }

                val modelName = (engine.state.value as? EngineState.Ready)?.modelName ?: "unknown"
                usageRepo.add(UsageRecord(System.currentTimeMillis(), "chat-agent", modelName, 0, totalTokens / 4))
                refreshUsage()
                persistCurrentSession()

                if (_currentSettings.value.ttsAutoSpeak && !tts.speaking.value) {
                    val full = _chatMessages.value.lastOrNull { it.timestamp == assistantMsg.timestamp }?.content.orEmpty()
                    if (full.isNotBlank()) tts.speak(full)
                }
            } catch (e: Exception) {
                ServerLog.log("Agent loop error: ${e.message}")
            } finally {
                _generating.value = false
                _agentStatus.value = null
            }
        }
    }

    fun stopChat() = engine.requestStop()

    fun clearChat() {
        if (!_generating.value) {
            startNewSession()
        }
    }
}
