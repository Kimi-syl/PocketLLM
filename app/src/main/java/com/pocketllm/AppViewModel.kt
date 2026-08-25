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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiMessage(val role: String, val content: String)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Application get() = getApplication()

    val engine = LlamaEngine
    private val settings = SettingsRepository(context)
    private val apiKeyRepo = ApiKeyRepository(context)
    private val hfClient = HuggingFaceClient { settings.current().hfToken }
    private val modelRepo = ModelRepository(context) { settings.current().hfToken }
    val server = ApiServer(settings, apiKeyRepo)

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

    init {
        refreshModels()
        refreshKeys()
        _currentSettings.value = settings.current()
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
                val history = _chatMessages.value + ChatUiMessage("user", trimmed)
                _chatMessages.value = history + ChatUiMessage("assistant", "")
                val replyIndex = _chatMessages.value.lastIndex
                val prompt = engine.chatPrompt(_chatMessages.value.take(replyIndex).map { it.role to it.content })
                if (prompt == null) {
                    _chatMessages.update { list ->
                        list.toMutableList().also { it[replyIndex] = ChatUiMessage("assistant", "[no model loaded]") }
                    }
                    return@launch
                }
                engine.generate(prompt, GenParams(maxTokens = 512)) { token ->
                    _chatMessages.update { list ->
                        list.toMutableList().also {
                            it[replyIndex] = it[replyIndex].copy(content = it[replyIndex].content + token)
                        }
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
