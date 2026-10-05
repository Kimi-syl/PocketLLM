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
import com.pocketllm.settings.SpeedPreset
import com.pocketllm.util.FishAudioTts
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
import com.pocketllm.ui.I18n

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
    /**
     * Stable identity for the message, set when the assistant placeholder is
     * created. Used by the agent path to find the row in [_chatMessages]
     * during streaming updates. Stays non-null after completion; UI never
     * reads it. Do NOT pack tracking info into [generatedTokens] — that
     * field is displayed to the user as a real token count.
     */
    val clientId: Long? = null,
)

private data class ChatMetrics(
    val promptTokens: Int,
    val generatedTokens: Int,
    val ttftMs: Long?,
    val tokensPerSecond: Float,
    val totalDurationMs: Long,
)

private const val ASSISTANT_ROLE_REQUEST_CODE = 4001

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

    // --- Advanced: long-term memory, cloned voice, CPU capabilities ---------
    val memoryStore = com.pocketllm.memory.MemoryStore.get(context)

    private val _memoryEntries =
        MutableStateFlow<List<com.pocketllm.memory.MemoryStore.Entry>>(emptyList())
    val memoryEntries: StateFlow<List<com.pocketllm.memory.MemoryStore.Entry>> = _memoryEntries

    val fishAudioTts = FishAudioTts(context)

    /** Cached: /proc/cpuinfo is read once, not on every recomposition. */
    val cpuFeatures: CpuInfo.Features by lazy { CpuInfo.features() }

    /**
     * True when the DotProd/FP16 kernel build (libpocketllm_v82) is the one that
     * actually got dlopen()ed - not merely that the hardware could run it.
     */
    val cpuKernelsOptimized: Boolean
        get() = com.pocketllm.llm.LlamaBridge.optimizedKernels

    // --- MCP ---------------------------------------------------------------
    private val _mcpTools = MutableStateFlow<List<com.pocketllm.mcp.McpClient.Tool>>(emptyList())
    val mcpTools: StateFlow<List<com.pocketllm.mcp.McpClient.Tool>> = _mcpTools

    /** Connects to the configured MCP server and lists its tools (real check). */
    fun testMcp(onResult: (String) -> Unit) {
        val s = settings.current()
        if (s.mcpServerUrl.isBlank()) {
            onResult("請先填寫伺服器 URL")
            return
        }
        viewModelScope.launch {
            val client = com.pocketllm.mcp.McpClient(s.mcpServerUrl, s.mcpServerToken)
            val r = client.tools()
            r.onSuccess { _mcpTools.value = it }
            onResult(r.exceptionOrNull()?.message ?: "OK — ${r.getOrNull()?.size ?: 0} tools")
        }
    }

    /** Invokes one MCP tool; used by the screen agent when it emits an mcp action. */
    suspend fun callMcpTool(name: String, argsJson: String): String {
        val s = settings.current()
        if (!s.mcpEnabled || s.mcpServerUrl.isBlank()) return "MCP is not enabled"
        val client = com.pocketllm.mcp.McpClient(s.mcpServerUrl, s.mcpServerToken)
        val args = runCatching { org.json.JSONObject(argsJson.ifBlank { "{}" }) }
            .getOrDefault(org.json.JSONObject())
        return client.call(name, args).fold(
            onSuccess = { it },
            onFailure = { "MCP error: ${it.message}" },
        )
    }

    private val tts = TtsManager(context)
    private val sessionRepo = ChatSessionRepository(context)

    val ttsReady: StateFlow<Boolean> = tts.ready
    val ttsSpeaking: StateFlow<Boolean> = tts.speaking
    val piperReady: StateFlow<Boolean> = tts.piperReady
    val piperInstalled: Boolean get() = tts.piperInstalled
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

    /** Raw safetensors checkpoints present in the models dir (inspect-only). */
    private val _safetensors = MutableStateFlow<List<GgufModel>>(emptyList())
    val safetensors: StateFlow<List<GgufModel>> = _safetensors

    /** HF checkpoint folders that can be converted to GGUF on device. */
    private val _convertDirs = MutableStateFlow<List<String>>(emptyList())
    val convertDirs: StateFlow<List<String>> = _convertDirs

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress

    /** URL of the model being downloaded right now, null when idle. Lets the
     *  UI render the progress bar inline under the row that started it. */
    private val _downloadingUrl = MutableStateFlow<String?>(null)
    val downloadingUrl: StateFlow<String?> = _downloadingUrl

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

    private val _uiAgentEnabled = MutableStateFlow(false)
    val uiAgentEnabled: StateFlow<Boolean> = _uiAgentEnabled

    fun toggleAgent() {
        _agentEnabled.value = !_agentEnabled.value
    }

    private val sandboxDir: java.io.File = java.io.File(context.filesDir, "sandbox").also { it.mkdirs() }
    private val readFileTool = com.pocketllm.agent.ReadFileTool(sandboxDir).also { it.setContext(context) }
    private val uiAgentExecutor = com.pocketllm.agent.UiAgentExecutor(
        context = context,
        mcpCall = { tool, args -> callMcpTool(tool, args) },
    )
    private val writeFileTool = com.pocketllm.agent.WriteFileTool(sandboxDir)
    private val runCodeTool = com.pocketllm.agent.RunCodeTool(sandboxDir)
    private val clipboardTool = com.pocketllm.agent.ClipboardReadTool(context)
    private val deviceInfoTool = com.pocketllm.agent.DeviceInfoTool(context)
    private val webSearchTool = com.pocketllm.agent.WebSearchTool {
        val s = _currentSettings.value
        com.pocketllm.agent.SearchConfig(
            engine = s.searchEngine,
            braveKey = s.braveKey,
            tavilyKey = s.tavilyKey,
            bingKey = s.bingKey,
            firecrawlKey = s.firecrawlKey,
        )
    }
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
        maxGenerationTokens = { _currentSettings.value.maxGenerationTokens },
    )

    private val _usageRecords = MutableStateFlow<List<UsageRecord>>(emptyList())
    val usageRecords: StateFlow<List<UsageRecord>> = _usageRecords

    // --- Session state ---
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions

    /** Live view of the in-memory log for the Logs screen. */
    val logLines: StateFlow<List<String>> = com.pocketllm.server.ServerLog.lines

    /** Re-read the persisted log file so the Logs screen shows history from prior runs. */
    fun refreshLog() {
        com.pocketllm.server.ServerLog.reloadFromDisk()
    }

    /** Clear all in-memory and on-disk log content. */
    fun clearLog() {
        com.pocketllm.server.ServerLog.clearLog()
    }
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId

    init {
        // Initialize the log file sink before anything else so crashes
        // during init are captured.
        com.pocketllm.server.ServerLog.init(context)
        com.pocketllm.server.PLog.sink = { line -> com.pocketllm.server.ServerLog.log(line) }
        installUncaughtExceptionHandler()
        refreshModels()
        refreshKeys()
        refreshUsage()
        _currentSettings.value = settings.current()
        tlsInfo.value = com.pocketllm.util.TlsCertManager.readFingerprint(context.filesDir)
        tts.setEngine(settings.current().ttsEngine)
        tts.setPiperSpeakerId(settings.current().ttsSpeakerId)
        _agentEnabled.value = settings.current().agentEnabled
        _uiAgentEnabled.value = settings.current().uiAgentEnabled
        // Pick up an already-downloaded Piper model without any download UI.
        if (settings.current().ttsEngine == "piper") {
            viewModelScope.launch { tts.preparePiper() }
        }
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
        _safetensors.value = modelRepo.listSafetensors()
        _convertDirs.value = modelRepo.listConvertibleDirs()
    }

    /**
     * On-device safetensors -> GGUF conversion for Llama-family checkpoints
     * (<= 1B). Runs the bundled torch-free converter inside the Alpine sandbox.
     */
    fun convertModelToGguf(
        dirName: String,
        onLine: (String) -> Unit,
        onDone: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val sandbox = com.pocketllm.util.SandboxManager(context)
            val outName = "$dirName-f16.gguf"
            val r = sandbox.convertToGguf(modelRepo.dir, dirName, outName) { line ->
                // convertToGguf logs from an IO thread; hop to Main for Compose state.
                viewModelScope.launch { onLine(line) }
            }
            refreshModels()
            onDone(r.fold(
                onSuccess = { "已產生 $outName" },
                onFailure = { "轉換失敗：${it.message}" },
            ))
        }
    }

    /**
     * Human-readable safetensors summary. Honest about the fact that llama.cpp
     * needs GGUF, so the file can be inspected but not executed as-is.
     */
    suspend fun safetensorsInfo(name: String): String = withContext(Dispatchers.IO) {
        val info = modelRepo.safetensorsInfo(name)
            ?: return@withContext "找不到檔案"
        info.fold(
            onSuccess = { it.summary() + "\n\n⚠ 這是原始 checkpoint，需轉換為 GGUF 才能在 llama.cpp 執行。" },
            onFailure = { "讀取失敗：${it.message}" },
        )
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
        val target = url.trim()
        viewModelScope.launch {
            _downloadProgress.value = 0f
            _downloadingUrl.value = target
            modelRepo.download(target, downloadCancelled) { progress -> _downloadProgress.value = progress }
                .onSuccess { ServerLog.log("Downloaded ${it.name}") }
                .onFailure { ServerLog.log("Download failed: ${it.message}") }
            _downloadProgress.value = null
            _downloadingUrl.value = null
            refreshModels()
        }
    }

    fun cancelDownload() {
        downloadCancelled.set(true)
    }

    fun loadModel(name: String) {
        val file = modelRepo.file(name)
        if (file == null || !file.exists()) {
            engine.setError("Model file $name not found")
            return
        }
        viewModelScope.launch {
            try {
                val resolved = settings.current().resolve()
                com.pocketllm.server.PLog.log("loadModel: preset=${settings.current().speedPreset} → ctx=${resolved.contextSize} batch=${resolved.batchSize} gpu=${resolved.gpuOffload}")
                engine.load(
                    file = file,
                    contextSize = resolved.contextSize,
                    threads = CpuInfo.recommendedThreads(),
                    gpuOffload = resolved.gpuOffload,
                    batchSize = resolved.batchSize,
                )
            } catch (t: Throwable) {
                com.pocketllm.server.PLog.error("loadModel failed", t)
                engine.setError(t.message ?: "Failed to load model")
            } finally {
                refreshModels()
            }
        }
    }

    fun importModel(uri: android.net.Uri, onComplete: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                modelRepo.importFromUri(uri)
            }
            result.onSuccess { modelName ->
                refreshModels()
                onComplete(true, modelName)
            }.onFailure { err ->
                onComplete(false, err.message ?: "Import failed")
            }
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

    fun updateBatchSize(sizeText: String) {
        val size = sizeText.toIntOrNull()?.coerceIn(128, 8192) ?: return
        updateSettings { it.copy(batchSize = size) }
    }

    fun updateSpeedPreset(preset: SpeedPreset) {
        updateSettings { it.copy(speedPreset = preset) }
    }

    fun updateMaxGenerationTokens(tokens: Int) {
        val clamped = tokens.coerceIn(64, 4096)
        updateSettings { it.copy(maxGenerationTokens = clamped) }
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

    // --- Floating companion -------------------------------------------------

    /**
     * Turning the bubble on also starts the overlay service; turning it off
     * tears the service down so no notification is left behind.
     */
    fun updateCompanionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.update { it.copy(companionEnabled = enabled) }
            _currentSettings.value = settings.current()
            val ctx = getApplication<android.app.Application>()
            if (enabled) {
                com.pocketllm.companion.CompanionOverlayService.start(ctx)
            } else {
                com.pocketllm.companion.CompanionOverlayService.stop(ctx)
            }
        }
    }

    fun updateCompanionPersona(text: String) {
        updateSettings { it.copy(companionPersona = text) }
    }

    fun updateCompanionTts(enabled: Boolean) {
        updateSettings { it.copy(companionTts = enabled) }
    }

    /** Shows/hides the microphone button in the panel. */
    fun updateCompanionVoiceInput(enabled: Boolean) {
        updateCompanionSetting { it.copy(companionVoiceInput = enabled) }
    }

    /** BCP-47 tag for recognition; blank means the device default. */
    fun updateCompanionSpeechLanguage(tag: String) {
        updateCompanionSetting { it.copy(companionSpeechLanguage = tag) }
    }

    fun updateCompanionSpeechPartialResults(enabled: Boolean) {
        updateCompanionSetting { it.copy(companionSpeechPartialResults = enabled) }
    }

    fun updateCompanionSpeechPreferOffline(enabled: Boolean) {
        updateCompanionSetting { it.copy(companionSpeechPreferOffline = enabled) }
    }

    // --- Companion identity & personality -----------------------------------

    fun updateCompanionName(name: String) {
        updateCompanionSetting { it.copy(companionName = name.take(24)) }
    }

    /**
     * Applying a preset also adopts its trait values, so picking "Straight
     * talker" actually sounds different. The user can then move the sliders
     * away from the preset.
     */
    fun updateCompanionStyle(presetId: String) {
        val preset = com.pocketllm.companion.CompanionPersonas.byId(presetId)
        updateCompanionSetting {
            it.copy(
                companionStyle = preset.id,
                companionWarmth = preset.warmth,
                companionDirectness = preset.directness,
                companionPlayfulness = preset.playfulness,
                companionVerbosity = preset.verbosity,
            )
        }
    }

    fun updateCompanionTraits(
        warmth: Int? = null,
        directness: Int? = null,
        playfulness: Int? = null,
        verbosity: Int? = null,
    ) {
        updateCompanionSetting {
            it.copy(
                companionWarmth = (warmth ?: it.companionWarmth).coerceIn(0, 100),
                companionDirectness = (directness ?: it.companionDirectness).coerceIn(0, 100),
                companionPlayfulness = (playfulness ?: it.companionPlayfulness).coerceIn(0, 100),
                companionVerbosity = (verbosity ?: it.companionVerbosity).coerceIn(0, 100),
            )
        }
    }

    fun updateCompanionGlyph(glyph: String) {
        updateCompanionSetting { it.copy(companionGlyph = glyph.take(4)) }
    }

    fun updateCompanionBubbleSize(sizeDp: Int) {
        updateCompanionSetting { it.copy(companionBubbleSize = sizeDp.coerceIn(36, 120)) }
    }

    fun updateCompanionBubbleAlpha(alpha: Int) {
        updateCompanionSetting { it.copy(companionBubbleAlpha = alpha.coerceIn(20, 100)) }
    }

    /** 0 restores the theme colour; see [AppSettings.companionBubbleColor]. */
    fun updateCompanionBubbleColor(argb: Long) {
        updateCompanionSetting { it.copy(companionBubbleColor = argb) }
    }

    fun updateCompanionBubbleShape(shapeId: String) {
        updateCompanionSetting { it.copy(companionBubbleShape = shapeId) }
    }

    /**
     * Apply a whole style bundle in one write.
     *
     * Deliberately one settings update rather than five: each update persists
     * and notifies the running overlay, so a loop of setters would write the
     * file and rebuild the bubble five times over.
     */
    fun applyCompanionBubblePreset(preset: com.pocketllm.companion.BubblePreset) {
        updateCompanionSetting {
            it.copy(
                companionBubbleSize = preset.size,
                companionBubbleShape = preset.shapeId,
                companionBubbleBorderWidth = preset.borderWidth,
                companionGlyphScale = preset.glyphScale,
                companionBubbleIdleAlpha = preset.idleAlpha,
            )
        }
    }

    fun updateCompanionBubbleBorderWidth(widthDp: Int) {
        updateCompanionSetting { it.copy(companionBubbleBorderWidth = widthDp.coerceIn(0, 8)) }
    }

    fun updateCompanionBubbleBorderColor(argb: Long) {
        updateCompanionSetting { it.copy(companionBubbleBorderColor = argb) }
    }

    fun updateCompanionGlyphScale(percent: Int) {
        updateCompanionSetting { it.copy(companionGlyphScale = percent.coerceIn(20, 80)) }
    }

    /** "off" draws the emoji glyph; a species draws the animated character. */
    fun updateCompanionCharacter(speciesId: String) {
        updateCompanionSetting {
            it.copy(
                companionCharacter = speciesId,
                // Live2D is a full-body illustration, so choosing it also leaves
                // the bubble: clipping a model into a 60dp circle wastes it.
                companionBareCharacter = if (speciesId == "live2d" || speciesId == "vrm") true else it.companionBareCharacter,
            )
        }
    }

    /** Which bundled Live2D sample model to show. */
    fun updateCompanionLive2DModel(modelName: String) {
        updateCompanionSetting { it.copy(companionLive2DModel = modelName) }
    }

    /** Which bundled VRM avatar to show. */
    fun updateCompanionVrmModel(modelName: String) {
        updateCompanionSetting { it.copy(companionVrmModel = modelName) }
    }

    /** Content URI of the user's own character art. Empty clears it. */
    fun updateCompanionImage(uri: String) {
        updateCompanionSetting {
            it.copy(companionImageUri = uri, companionCharacter = if (uri.isBlank()) it.companionCharacter else "image")
        }
    }

    /** Frame grid of the chosen image; 1x1 is a single still picture. */
    fun updateCompanionImageGrid(columns: Int, rows: Int) {
        updateCompanionSetting {
            it.copy(
                companionImageColumns = columns.coerceIn(1, 24),
                companionImageRows = rows.coerceIn(1, 24),
            )
        }
    }

    /** Sprite sheet playback rate. */
    fun updateCompanionImageFps(fps: Int) {
        updateCompanionSetting { it.copy(companionImageFps = fps.coerceIn(1, 30)) }
    }

    /** "auto" lets her face mirror the user's mood; anything else pins it. */
    fun updateCompanionExpression(expressionId: String) {
        updateCompanionSetting { it.copy(companionExpression = expressionId) }
    }

    /** Free-standing character instead of a bubble-wrapped one. */
    fun updateCompanionBareCharacter(bare: Boolean) {
        updateCompanionSetting { it.copy(companionBareCharacter = bare) }
    }

    /** Height of the free-standing character in dp. */
    fun updateCompanionCharacterSize(sizeDp: Int) {
        updateCompanionSetting { it.copy(companionCharacterSize = sizeDp.coerceIn(80, 420)) }
    }

    /**
     * Put the panel back where it first appears.
     *
     * -1 is the "never placed" sentinel the service already understands for both
     * axes, so this reuses that path rather than inventing a second one.
     */
    fun resetCompanionPanelPosition() {
        updateCompanionSetting {
            it.copy(companionPanelX = -1, companionPanelY = -1)
        }
    }

    /** Restore the panel's default size. */
    fun resetCompanionPanelSize() {
        updateCompanionSetting { it.copy(companionPanelWidth = 360, companionPanelHeight = 470) }
    }

    fun updateCompanionBubbleIdleAlpha(percent: Int) {
        updateCompanionSetting { it.copy(companionBubbleIdleAlpha = percent.coerceIn(20, 100)) }
    }

    fun updateCompanionBubbleDoubleTap(actionId: String) {
        updateCompanionSetting { it.copy(companionBubbleDoubleTap = actionId) }
    }

    fun updateCompanionBubbleLongPress(actionId: String) {
        updateCompanionSetting { it.copy(companionBubbleLongPress = actionId) }
    }

    fun updateCompanionSpeechRate(rate: Float) {
        updateCompanionSetting { it.copy(companionSpeechRate = rate.coerceIn(0.5f, 2f)) }
    }

    fun updateCompanionSpeechPitch(pitch: Float) {
        updateCompanionSetting { it.copy(companionSpeechPitch = pitch.coerceIn(0.5f, 2f)) }
    }

    fun updateCompanionPanelFontScale(scale: Int) {
        updateCompanionSetting { it.copy(companionPanelFontScale = scale.coerceIn(80, 140)) }
    }

    fun updateCompanionSnapToEdge(enabled: Boolean) {
        updateCompanionSetting { it.copy(companionSnapToEdge = enabled) }
    }

    /**
     * Put the bubble back in its default corner.
     *
     * -1 is the "never placed" sentinel the service already understands, so this
     * reuses that path rather than inventing a second one. It also takes effect
     * live: the service is told to re-read settings and moves the window.
     */
    fun resetCompanionBubblePosition() {
        updateCompanionSetting {
            it.copy(companionBubbleX = -1, companionBubbleY = 0)
        }
    }

    fun updateCompanionMemoryEnabled(enabled: Boolean) {
        updateSettings { it.copy(companionMemoryEnabled = enabled) }
    }

    // --- Companion mood journal ---------------------------------------------

    private val companionMood = com.pocketllm.companion.MoodJournalStore(application)

    private val _moodEntries =
        MutableStateFlow<List<com.pocketllm.companion.MoodEntry>>(emptyList())
    val moodEntries: StateFlow<List<com.pocketllm.companion.MoodEntry>> = _moodEntries

    private val _moodSummary = MutableStateFlow("No check-ins yet.")
    val moodSummary: StateFlow<String> = _moodSummary

    /** Average per day over the last fortnight, oldest first, null for gaps. */
    private val _moodTrend = MutableStateFlow<List<Double?>>(emptyList())
    val moodTrend: StateFlow<List<Double?>> = _moodTrend

    fun refreshMood() {
        viewModelScope.launch(Dispatchers.IO) {
            _moodEntries.value = companionMood.all()
            _moodSummary.value = companionMood.summary()
            _moodTrend.value = companionMood.dailyAverages(14)
        }
    }

    fun clearMoodJournal() {
        viewModelScope.launch(Dispatchers.IO) {
            companionMood.clear()
            _moodEntries.value = emptyList()
            _moodSummary.value = companionMood.summary()
            _moodTrend.value = companionMood.dailyAverages(14)
        }
    }

    // --- Companion reminders ------------------------------------------------

    private val companionReminders =
        com.pocketllm.companion.CompanionReminderStore(application)

    private val _reminders =
        MutableStateFlow<List<com.pocketllm.companion.CompanionReminder>>(emptyList())
    val reminders: StateFlow<List<com.pocketllm.companion.CompanionReminder>> = _reminders

    fun refreshReminders() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { companionReminders.prune() }
            _reminders.value = companionReminders.all()
        }
    }

    /** Also cancels the pending alarm, not just the stored entry. */
    fun cancelReminder(context: android.content.Context, id: String) {
        com.pocketllm.companion.ReminderScheduler.cancel(context, id)
        viewModelScope.launch(Dispatchers.IO) {
            companionReminders.remove(id)
            _reminders.value = companionReminders.all()
        }
    }

    fun updateCompanionMemoryExtraction(enabled: Boolean) {
        updateSettings { it.copy(companionMemoryExtraction = enabled) }
    }

    // --- Proactive check-ins ------------------------------------------------

    fun updateCompanionProactiveNudges(enabled: Boolean) {
        updateCompanionSetting { it.copy(companionProactiveNudges = enabled) }
    }

    fun updateCompanionNudgeInterval(minutes: Int) {
        updateCompanionSetting { it.copy(companionNudgeIntervalMinutes = minutes.coerceIn(15, 24 * 60)) }
    }

    fun updateCompanionQuietHours(startHour: Int, endHour: Int) {
        updateCompanionSetting {
            it.copy(
                companionQuietStartHour = startHour.coerceIn(0, 23),
                companionQuietEndHour = endHour.coerceIn(0, 23),
            )
        }
    }

    /**
     * Writes a companion setting and then pushes it to the live overlay.
     *
     * The refresh must happen *after* the write completes: [updateSettings]
     * launches a coroutine, so refreshing straight away could have the service
     * read the file before the new value landed.
     */
    private fun updateCompanionSetting(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settings.update(transform)
            _currentSettings.value = settings.current()
            com.pocketllm.companion.CompanionOverlayService.sync(getApplication())
        }
    }

    fun updateCloudEnabled(enabled: Boolean) {
        updateSettings { it.copy(cloudEnabled = enabled) }
    }

    fun updateCloudBaseUrl(url: String) {
        updateSettings { it.copy(cloudBaseUrl = url.trim()) }
    }

    // --- Companion memory ---------------------------------------------------

    private val companionMemory = com.pocketllm.companion.CompanionMemory(application)

    private val _memoryFacts = MutableStateFlow<List<com.pocketllm.companion.MemoryFact>>(emptyList())
    val memoryFacts: StateFlow<List<com.pocketllm.companion.MemoryFact>> = _memoryFacts

    /** Facts that may no longer be true; surfaced so the user can decide. */
    private val _staleMemoryCount = MutableStateFlow(0)
    val staleMemoryCount: StateFlow<Int> = _staleMemoryCount

    /** One-off message from an import/export action, cleared once shown. */
    private val _memoryNotice = MutableStateFlow<String?>(null)
    val memoryNotice: StateFlow<String?> = _memoryNotice

    private fun refreshMemory() {
        _memoryFacts.value = companionMemory.all().sortedByDescending { it.createdAt }
        _staleMemoryCount.value = companionMemory.stale().size
    }

    fun refreshCompanionMemory() = refreshMemory()

    fun addMemoryFact(text: String) {
        companionMemory.add(text)
        refreshMemory()
    }

    fun updateMemoryFact(id: String, text: String) {
        companionMemory.update(id, text)
        refreshMemory()
    }

    fun removeMemoryFact(id: String) {
        companionMemory.remove(id)
        refreshMemory()
    }

    fun setMemoryFactPinned(id: String, pinned: Boolean) {
        companionMemory.setPinned(id, pinned)
        refreshMemory()
    }

    fun setMemoryFactCategory(id: String, category: com.pocketllm.companion.MemoryCategory) {
        companionMemory.setCategory(id, category)
        refreshMemory()
    }

    /** Confirm a stale fact is still true, which resets its staleness clock. */
    fun confirmMemoryFact(id: String) {
        companionMemory.reinforce(id)
        refreshMemory()
    }

    fun forgetStaleMemory() {
        val dropped = companionMemory.forgetStale()
        refreshMemory()
        _memoryNotice.value = if (dropped == 0) "Nothing looked out of date." else "Forgot $dropped."
    }

    fun exportCompanionMemory(): String = companionMemory.exportJson()

    fun importCompanionMemory(raw: String) {
        val added = runCatching { companionMemory.importJson(raw) }.getOrDefault(-1)
        refreshMemory()
        _memoryNotice.value = when {
            added < 0 -> "That didn't look like an export."
            added == 0 -> "Nothing new in there."
            // Import merges rather than replaces, so say so plainly.
            else -> "Added $added new fact${if (added == 1) "" else "s"}."
        }
    }

    fun dismissMemoryNotice() {
        _memoryNotice.value = null
    }

    fun clearCompanionMemory() {
        companionMemory.clear()
        refreshMemory()
    }

    // --- Companion profiles -------------------------------------------------

    private val companionProfiles = com.pocketllm.companion.CompanionProfileStore(application)

    private val _profiles =
        MutableStateFlow<List<com.pocketllm.companion.CompanionProfile>>(emptyList())
    val profiles: StateFlow<List<com.pocketllm.companion.CompanionProfile>> = _profiles

    private fun refreshProfiles() {
        _profiles.value = companionProfiles.all().sortedByDescending { it.createdAt }
    }

    fun refreshCompanionProfiles() = refreshProfiles()

    /** Snapshot the companion currently in settings as a reusable profile. */
    fun saveCurrentAsProfile(name: String) {
        val s = _currentSettings.value
        viewModelScope.launch {
            companionProfiles.save(
                com.pocketllm.companion.CompanionProfile(
                    name = name.trim().ifBlank { s.companionName },
                    style = s.companionStyle,
                    warmth = s.companionWarmth,
                    directness = s.companionDirectness,
                    playfulness = s.companionPlayfulness,
                    verbosity = s.companionVerbosity,
                    persona = s.companionPersona,
                    glyph = s.companionGlyph,
                    bubbleColor = s.companionBubbleColor,
                )
            )
            refreshProfiles()
        }
    }

    /** Apply a saved profile onto the active companion. */
    fun applyProfile(id: String) {
        val profile = _profiles.value.firstOrNull { it.id == id } ?: return
        updateCompanionSetting {
            it.copy(
                companionName = profile.name,
                companionStyle = profile.style,
                companionWarmth = profile.warmth,
                companionDirectness = profile.directness,
                companionPlayfulness = profile.playfulness,
                companionVerbosity = profile.verbosity,
                companionPersona = profile.persona,
                companionGlyph = profile.glyph,
                companionBubbleColor = profile.bubbleColor,
            )
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            companionProfiles.delete(id)
            refreshProfiles()
        }
    }

    fun exportProfiles(): String = companionProfiles.export()

    /**
     * Import a shared/exported bundle. Returns how many were added (0 if they
     * were all already in the library); throws [IllegalArgumentException] with
     * a readable message on bad input, since the text comes from a paste box.
     */
    suspend fun importProfiles(text: String): Int {
        val added = companionProfiles.importAll(text)
        refreshProfiles()
        return added
    }

    init {
        refreshMemory()
        refreshProfiles()
    }

    fun updateCloudApiKey(key: String) {
        updateSettings { it.copy(cloudApiKey = key.trim()) }
    }

    fun updateCloudModel(model: String) {
        updateSettings { it.copy(cloudModel = model.trim()) }
    }

    /** Called after the user returns from the overlay-permission screen. */
    fun restartCompanionIfEnabled() {
        val ctx = getApplication<android.app.Application>()
        if (_currentSettings.value.companionEnabled) {
            com.pocketllm.companion.CompanionOverlayService.start(ctx)
        }
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

    fun updateUiAgentEnabled(enabled: Boolean) {
        updateSettings { it.copy(uiAgentEnabled = enabled) }
        _uiAgentEnabled.value = enabled
    }

    // --- Advanced settings ---------------------------------------------------

    fun updateUiAgentGrammar(enabled: Boolean) =
        updateSettings { it.copy(uiAgentGrammar = enabled) }

    fun updateMemoryEnabled(enabled: Boolean) =
        updateSettings { it.copy(memoryEnabled = enabled) }

    fun updateVoiceCloneEnabled(enabled: Boolean) =
        updateSettings { it.copy(voiceCloneEnabled = enabled) }

    fun updateFishAudioKey(value: String) =
        updateSettings { it.copy(fishAudioKey = value) }

    fun updateFishAudioVoiceId(value: String) =
        updateSettings { it.copy(fishAudioVoiceId = value) }

    fun updateMcpEnabled(enabled: Boolean) {
        updateSettings { it.copy(mcpEnabled = enabled) }
        // Refresh the tool list so the agent prompt has something to advertise.
        if (enabled && settings.current().mcpServerUrl.isNotBlank()) testMcp { }
    }

    fun updateMcpServerUrl(value: String) =
        updateSettings { it.copy(mcpServerUrl = value) }

    fun updateMcpServerToken(value: String) =
        updateSettings { it.copy(mcpServerToken = value) }

    // --- Dynamic assistant role (AssistStructure screen context) -------------

    private val _assistContext = MutableStateFlow("")
    val assistContext: StateFlow<String> = _assistContext

    fun assistantRoleHeld(): Boolean = runCatching {
        context.getSystemService(android.app.role.RoleManager::class.java)
            ?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true
    }.getOrDefault(false)

    fun assistantRoleAvailable(): Boolean = runCatching {
        context.getSystemService(android.app.role.RoleManager::class.java)
            ?.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) == true
    }.getOrDefault(false)

    /**
     * Opens the system's "Digital assistant" settings.
     *
     * RoleManager.createRequestRoleIntent is the stock path, but it is
     * unavailable on MIUI and several AOSP forks (isRoleAvailable returns false
     * and the old code silently did nothing). Walk a cascade of intents that the
     * OEM settings apps actually implement, and report what happened so the UI
     * can tell the user instead of appearing broken.
     */
    fun openAssistantSettings(activity: android.app.Activity): String {
        // 1) Role request — stock Android 10+.
        runCatching {
            val rm = context.getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT)) {
                activity.startActivityForResult(
                    rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT),
                    ASSISTANT_ROLE_REQUEST_CODE,
                )
                return "已開啟助理角色授權視窗"
            }
        }

        // 2) OEM settings screens, most specific first.
        val candidates = listOf(
            "已開啟數位助理設定" to android.content.Intent("android.settings.VOICE_INPUT_SETTINGS"),
            "已開啟預設應用程式設定" to android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
            ),
            "已開啟應用程式設定" to android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}"),
            ),
            "已開啟系統設定" to android.content.Intent(android.provider.Settings.ACTION_SETTINGS),
        )
        for ((message, intent) in candidates) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { activity.startActivity(intent) }.isSuccess) return message
        }
        return "無法自動開啟，請手動前往：設定 → 應用程式 → 預設應用程式 → 數位助理"
    }

    fun refreshAssistContext() {
        _assistContext.value = com.pocketllm.voice.AssistContextHolder.lastContext
    }

    /** Loads stored facts for the settings list. */
    fun refreshMemoryStore() {
        viewModelScope.launch {
            _memoryEntries.value = withContext(Dispatchers.IO) { memoryStore.all() }
        }
    }

    fun rememberFact(subject: String, fact: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { memoryStore.remember(subject, fact) }
            refreshMemoryStore()
        }
    }

    fun forgetFact(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { memoryStore.forget(id) }
            refreshMemoryStore()
        }
    }

    fun clearMemory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { memoryStore.clear() }
            refreshMemoryStore()
        }
    }

    /** Plays a sample through the cloning backend; [onResult] gets "OK" or the error. */
    fun testVoiceClone(text: String, onResult: (String) -> Unit) {
        val s = settings.current()
        viewModelScope.launch {
            val r = fishAudioTts.speak(text, s.fishAudioKey, s.fishAudioVoiceId, s.fishAudioModel)
            onResult(r.exceptionOrNull()?.message ?: "OK")
        }
    }

    /** Facts block for prompt injection; empty when memory is off or empty. */
    fun memoryPromptBlock(): String {
        val s = settings.current()
        if (!s.memoryEnabled) return ""
        return runCatching { memoryStore.promptBlock(s.memoryMaxEntries) }.getOrDefault("")
    }

    fun updateUiAgentTuning(maxSteps: Int, maxRetries: Int) {
        updateSettings { it.copy(uiAgentMaxSteps = maxSteps, uiAgentMaxRetries = maxRetries) }
    }

    fun updateTtsEngine(engine: String) {
        updateSettings { it.copy(ttsEngine = engine) }
        tts.setEngine(engine)
        if (engine == "piper") {
            // Load if present; the download itself starts on first use.
            viewModelScope.launch { tts.preparePiper() }
        }
    }

    /** Offline (multi-speaker) voice selection for the Piper/sherpa engine. */
    fun updateTtsSpeakerId(id: Int) {
        updateSettings { it.copy(ttsSpeakerId = id) }
        tts.setPiperSpeakerId(id)
    }

    fun retryPiperDownload() {
        viewModelScope.launch { tts.retryPiper() }
    }

    /** Experimental Turnip GPU driver opt-in (off by default; may crash on some GPUs). */
    fun isTurnipEnabled(): Boolean =
        java.io.File(context.filesDir, "turnip.on").exists()

    fun updateTurnipEnabled(enabled: Boolean) {
        val f = java.io.File(context.filesDir, "turnip.on")
        if (enabled) f.createNewFile() else f.delete()
        com.pocketllm.server.PLog.log("Turnip driver ${if (enabled) "ENABLED" else "disabled"} (takes effect on app restart)")
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

    val chatStorageSizeBytes = MutableStateFlow(0L)

    fun refreshChatStorageSize() {
        viewModelScope.launch {
            chatStorageSizeBytes.value = sessionRepo.getStorageSizeBytes()
        }
    }

    fun clearChatStorage() {
        viewModelScope.launch {
            sessionRepo.clearAll()
            _sessions.value = emptyList()
            chatStorageSizeBytes.value = 0L
        }
    }

    fun updateDefaultChatModel(model: String) {
        updateSettings { it.copy(chatModel = model) }
    }

    fun updatePerChatModelIndependent(independent: Boolean) {
        updateSettings { it.copy(perChatModelIndependent = independent) }
    }

    fun updateTitleSummaryModel(model: String) {
        updateSettings { it.copy(titleSummaryModel = model) }
    }

    fun updateTitleSummaryEnabled(enabled: Boolean) {
        updateSettings { it.copy(titleSummaryEnabled = enabled) }
    }

    fun updateSummaryModel(model: String) {
        updateSettings { it.copy(summaryModel = model) }
    }

    fun updateChatSuggestionModel(model: String) {
        updateSettings { it.copy(chatSuggestionModel = model) }
    }

    fun updateChatSuggestionEnabled(enabled: Boolean) {
        updateSettings { it.copy(chatSuggestionEnabled = enabled) }
    }

    fun updateCompressionModel(model: String) {
        updateSettings { it.copy(compressionModel = model) }
    }

    fun updateTranslationModel(model: String) {
        updateSettings { it.copy(translationModel = model) }
    }

    fun updateOcrModel(model: String) {
        updateSettings { it.copy(ocrModel = model) }
    }

    fun updateTitleSummaryPrompt(prompt: String) {
        updateSettings { it.copy(titleSummaryPrompt = prompt) }
    }

    fun updateSummaryPrompt(prompt: String) {
        updateSettings { it.copy(summaryPrompt = prompt) }
    }

    fun updateChatSuggestionPrompt(prompt: String) {
        updateSettings { it.copy(chatSuggestionPrompt = prompt) }
    }

    fun updateCompressionPrompt(prompt: String) {
        updateSettings { it.copy(compressionPrompt = prompt) }
    }

    fun updateTranslationPrompt(prompt: String) {
        updateSettings { it.copy(translationPrompt = prompt) }
    }

    fun updateOcrPrompt(prompt: String) {
        updateSettings { it.copy(ocrPrompt = prompt) }
    }

    fun updateSettingsStyle(style: String) {
        updateSettings { it.copy(settingsStyle = style) }
    }

    fun updateAppLanguage(lang: String) {
        updateSettings { it.copy(appLanguage = lang) }
    }

    fun updateFontScale(scale: Float) {
        updateSettings { it.copy(fontScale = scale) }
    }

    fun updateHighContrast(enabled: Boolean) {
        updateSettings { it.copy(highContrast = enabled) }
    }

    fun updateHapticFeedback(enabled: Boolean) {
        updateSettings { it.copy(hapticFeedback = enabled) }
    }

    fun updateReduceMotion(enabled: Boolean) {
        updateSettings { it.copy(reduceMotion = enabled) }
    }

    fun updateLargeTouchTargets(enabled: Boolean) {
        updateSettings { it.copy(largeTouchTargets = enabled) }
    }

    fun updateSandboxInstalled(installed: Boolean) {
        updateSettings { it.copy(sandboxInstalled = installed) }
    }

    /**
     * Real sandbox provisioning: extract the bundled Alpine rootfs, then install
     * python3/pip inside it with apk. Statuses come from actually running the
     * commands, not from a lookup table.
     */
    fun provisionSandbox(
        onDone: (success: Boolean, statuses: Map<String, String>, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            val sandbox = com.pocketllm.util.SandboxManager(context)
            val install = sandbox.ensureInstalled()
            if (install.isFailure) {
                updateSandboxInstalled(false)
                onDone(false, emptyMap(), "解壓 rootfs 失敗：${install.exceptionOrNull()?.message}")
                return@launch
            }
            // The Alpine minirootfs ships no interpreter; add one.
            val apkRes = sandbox.run("apk add --no-cache python3 py3-pip", timeoutSec = 240)
            val statuses = linkedMapOf<String, String>()
            statuses["python"] = sandbox.run("python3 -V").getOrElse { "未安裝" }.trim()
            statuses["pip"] = sandbox.run("python3 -m pip --version").getOrElse { "未安裝" }.trim().take(60)
            statuses["node"] = "可用 apk add nodejs 安裝"
            statuses["git"] = "可用 apk add git 安裝"
            statuses["ssh"] = "可用 apk add openssh 安裝"
            statuses["net"] = if (apkRes.isSuccess) "已就緒（apk 聯網成功）" else "apk 失敗：${apkRes.exceptionOrNull()?.message?.take(60)}"
            updateSandboxInstalled(true)
            onDone(true, statuses, "沙盒環境已就緒")
        }
    }

    fun addSandboxWorkspace(name: String) {
        updateSettings { current ->
            val list = current.sandboxWorkspaces.toMutableList()
            if (!list.contains(name)) list.add(name)
            current.copy(sandboxWorkspaces = list)
        }
    }

    fun removeSandboxWorkspace(name: String) {
        updateSettings { current ->
            current.copy(sandboxWorkspaces = current.sandboxWorkspaces.filter { it != name })
        }
    }

    fun addMountedFolder(path: String) {
        updateSettings { current ->
            val list = current.sandboxMountedFolders.toMutableList()
            if (!list.contains(path)) list.add(path)
            current.copy(sandboxMountedFolders = list)
        }
    }

    fun removeMountedFolder(path: String) {
        updateSettings { current ->
            current.copy(sandboxMountedFolders = current.sandboxMountedFolders.filter { it != path })
        }
    }

    fun updateNetworkProxy(proxy: String, enabled: Boolean) {
        updateSettings { it.copy(networkProxy = proxy, proxyEnabled = enabled) }
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
        if (_uiAgentEnabled.value && looksLikeUiCommand(enriched)) {
            sendChatWithUiAgent(enriched)
        } else if (_agentEnabled.value) {
            sendChatWithAgent(enriched)
        } else {
            sendChatPlain(enriched)
        }
    }


    /**
     * Keyword gate for the UI agent: with the service on, only messages that
     * actually ask the agent to *do* something on screen route to it — plain
     * conversation must never be hijacked just because accessibility is on.
     * A message can always force the agent by prefixing "@ui".
     */
    private fun looksLikeUiCommand(text: String): Boolean {
        val t = text.trim()
        if (t.startsWith("@ui", ignoreCase = true)) return true
        if (t.length > 120) return false   // long prose is conversation, not a command
        val words = t.lowercase().split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotBlank() }
        if (words.size > 12) return false  // ditto
        val verbs = setOf("open", "close", "tap", "click", "press", "type", "swipe",
                          "scroll", "go", "back", "launch", "start", "stop", "play",
                          "pause", "toggle", "turn", "set", "enable", "disable",
                          "打開", "關閉", "點", "按", "輸入", "滑動", "返回", "啟動", "切換", "關掉")
        val subjects = setOf("app", "screen", "settings", "browser", "youtube", "camera",
                             "wifi", "bluetooth", "volume", "brightness", "keyboard",
                             "通知", "相機", "瀏覽器", "設定", "螢幕", "音量", "亮度", "鍵盤")
        val verbHit = words.any { it in verbs }
        val subjectHit = words.any { it in subjects }
        return verbHit && (subjectHit || words.size <= 4)
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
                val result = engine.generate(prompt, GenParams(maxTokens = _currentSettings.value.maxGenerationTokens)) { token ->
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

    /**
     * UI accessibility agent path: runs the ReAct [UiAgentLoop] against the
     * on-screen UI tree instead of the tool registry. Requires the
     * accessibility service to be enabled (it publishes the node snapshot);
     * without it the request degrades to a plain chat answer that explains
     * why, rather than silently pretending to act.
     */
    private fun sendChatWithUiAgent(trimmed: String) {
        viewModelScope.launch {
            com.pocketllm.server.ServerLog.log("sendChatWithUiAgent: starting")
            _generating.value = true
            try {
                val userTs = System.currentTimeMillis()
                _chatMessages.value = _chatMessages.value + ChatUiMessage("user", trimmed, timestamp = userTs)
                val replyIndex = _chatMessages.value.lastIndex

                fun setReply(text: String) {
                    _chatMessages.update { list ->
                        list.toMutableList().also { it[replyIndex] = it[replyIndex].copy(content = text) }
                    }
                }

                if (!com.pocketllm.agent.UiAccessibilityService.isRunning) {
                    setReply(
                        if (I18n.isEnglish(_currentSettings.value.appLanguage))
                            "The screen-reading service isn't active. Enable PocketLLM in Settings → Accessibility → Installed services, then try again."
                        else
                            "螢幕讀取服務尚未啟用。請在 設定 → 無障礙 → 已安裝的服務 中啟用 PocketLLM，再試一次。"
                    )
                    return@launch
                }

                val tuning = _currentSettings.value
                val loop = com.pocketllm.agent.UiAgentLoop(
                    engine = engine,
                    executor = uiAgentExecutor,
                    maxSteps = tuning.uiAgentMaxSteps.coerceIn(1, 16),
                    maxRetries = tuning.uiAgentMaxRetries.coerceIn(1, 6),
                    useGrammar = tuning.uiAgentGrammar,
                    mcpToolsBlock = if (tuning.mcpEnabled) {
                        _mcpTools.value.joinToString("\n") { "- ${it.name}: ${it.description}" }
                    } else "",
                )
                val logLines = mutableListOf<String>()
                val result = loop.run(trimmed) { line ->
                    logLines += line
                    com.pocketllm.server.ServerLog.log("ui-agent: $line")
                }
                when (result) {
                    is com.pocketllm.agent.UiAgentLoop.UiResult.Done ->
                        setReply(result.answer ?: logLines.lastOrNull() ?: "Done.")
                    is com.pocketllm.agent.UiAgentLoop.UiResult.Error ->
                        setReply("UI agent error: ${result.message}")
                }
            } catch (e: Throwable) {
                com.pocketllm.server.ServerLog.log("sendChatWithUiAgent: failed: ${e.message}")
                _chatMessages.value = _chatMessages.value + ChatUiMessage(
                    "assistant", "UI agent failed: ${e.message}", timestamp = System.currentTimeMillis(),
                )
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
                // sendChat, session switch, or clearChat). The clientId is unique
                // enough to identify the assistant placeholder across updates and
                // is never displayed.
                val assistantClientId = System.nanoTime()
                _chatMessages.value = _chatMessages.value + ChatUiMessage("user", trimmed, timestamp = userTs)
                val assistantMsg = ChatUiMessage(
                    "assistant", "", timestamp = System.currentTimeMillis(),
                    clientId = assistantClientId,
                )
                _chatMessages.value = _chatMessages.value + assistantMsg

                val turn = mutableListOf<com.pocketllm.agent.ToolCallUi>()
                val genStart = System.nanoTime()
                var firstTokenNs: Long? = null

                fun setReplyText(text: String) {
                    if (firstTokenNs == null && text.isNotEmpty()) firstTokenNs = System.nanoTime()
                    _chatMessages.update { list ->
                        list.toMutableList().also {
                            val idx = it.indexOfLast { m -> m.clientId == assistantMsg.clientId }
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
                            val idx = it.indexOfLast { m -> m.clientId == assistantMsg.clientId }
                            if (idx >= 0) {
                                it[idx] = it[idx].copy(toolCalls = turn.toList())
                            }
                        }
                    }
                }

                val history = _chatMessages.value
                    .filter { it.clientId != assistantMsg.clientId }
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
                // Use the real JNI-reported token count, not a character-based estimate.
                val generatedTokens = outcome.totalGeneratedTokens.coerceAtMost(100_000)
                val tps = if (totalMs > 0) generatedTokens.toFloat() / totalMs * 1000f else 0f

                _chatMessages.update { list ->
                    list.toMutableList().also {
                        val idx = it.indexOfLast { m -> m.clientId == assistantMsg.clientId }
                        if (idx >= 0) {
                            it[idx] = it[idx].copy(
                                content = if (finalText.isBlank() && it[idx].content.isBlank()) "(no response)" else finalText.ifBlank { it[idx].content },
                                toolCalls = turn.toList(),
                                generatedTokens = generatedTokens,
                                timeToFirstTokenMs = ttftMs,
                                tokensPerSecond = tps,
                                totalDurationMs = totalMs,
                            )
                        }
                    }
                }

                val modelName = (engine.state.value as? EngineState.Ready)?.modelName ?: "unknown"
                usageRepo.add(UsageRecord(System.currentTimeMillis(), "chat-agent", modelName, 0, generatedTokens))
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
