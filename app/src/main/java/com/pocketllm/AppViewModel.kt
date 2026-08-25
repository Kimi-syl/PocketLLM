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

data class ChatUiMessage(val role: String, val content: String)

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

    val ttsReady: StateFlow<Boolean> = tts.ready
    val ttsSpeaking: StateFlow<Boolean> = tts.speaking

    val server = ApiServer(context, settings, apiKeyRepo, usageRepo) { refreshUsage() }

    fun speakOrStop(text: String) {
        if (tts.speaking.value) tts.stop() else tts.speak(text)
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

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating

    private val _webSearchEnabled = MutableStateFlow(false)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled

    fun toggleWebSearch() {
        _webSearchEnabled.value = !_webSearchEnabled.value
    }

    private val _usageRecords = MutableStateFlow<List<UsageRecord>>(emptyList())
    val usageRecords: StateFlow<List<UsageRecord>> = _usageRecords

    init {
        refreshModels()
        refreshKeys()
        refreshUsage()
        _currentSettings.value = settings.current()
        tlsInfo.value = com.pocketllm.util.TlsCertManager.readFingerprint(context.filesDir)
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
            engine.load(file, settings.current().contextSize, CpuInfo.recommendedThreads())
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
        viewModelScope.launch {
            _generating.value = true
            try {
                val systemPrompt = _currentSettings.value.startupPrompt.trim()
                val visibleHistory = _chatMessages.value + ChatUiMessage("user", trimmed)
                _chatMessages.value = visibleHistory + ChatUiMessage("assistant", "")
                val replyIndex = _chatMessages.value.lastIndex

                fun setReply(text: String) {
                    _chatMessages.update { list ->
                        list.toMutableList().also { it[replyIndex] = it[replyIndex].copy(content = text) }
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
                        appendLine("Web search results for \"${trimmed}\":")
                        results.forEachIndexed { i, r ->
                            appendLine("\${i + 1}. \${r.title} — \${r.snippet} (\${r.url})")
                        }
                        append("Use these results when answering the user's question.")
                    }
                    ServerLog.log("Web search: ${'$'}{results.size} results for \"${'$'}trimmed\"")
                    setReply("")
                }

                val promptMessages = buildList {
                    if (systemPrompt.isNotEmpty()) add("system" to systemPrompt)
                    if (webContext != null) add("system" to webContext!!)
                    addAll(visibleHistory.map { it.role to it.content })
                }
                val prompt = engine.chatPrompt(promptMessages)
                if (prompt == null) {
                    _chatMessages.update { list ->
                        list.toMutableList().also { it[replyIndex] = ChatUiMessage("assistant", "[no model loaded]") }
                    }
                    return@launch
                }
                val modelName = (engine.state.value as? EngineState.Ready)?.modelName ?: "unknown"
                val result = engine.generate(prompt, GenParams(maxTokens = 512)) { token ->
                    _chatMessages.update { list ->
                        list.toMutableList().also {
                            it[replyIndex] = it[replyIndex].copy(content = it[replyIndex].content + token)
                        }
                    }
                }
                result?.let {
                    usageRepo.add(UsageRecord(System.currentTimeMillis(), "chat", modelName, it.promptTokens, it.generatedTokens))
                    refreshUsage()
                    if (_currentSettings.value.ttsAutoSpeak && !tts.speaking.value) {
                        val full = _chatMessages.value.getOrNull(replyIndex)?.content.orEmpty()
                        if (full.isNotBlank()) tts.speak(full)
                    }
                }
            } finally {
                _generating.value = false
            }
        }
    }

    fun stopChat() = engine.requestStop()

    fun clearChat() {
        if (!_generating.value) _chatMessages.value = emptyList()
    }
}
