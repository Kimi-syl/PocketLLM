package com.pocketllm.llm

import com.pocketllm.server.PLog
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object LlamaEngine : ChatEngine {

    private val dispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "llama-engine").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val mutex = Mutex()

    private val _state = MutableStateFlow<EngineState>(EngineState.Empty)
    override val state: StateFlow<EngineState> = _state

    fun setError(message: String) {
        _state.value = EngineState.Error(message)
    }

    @Volatile
    private var handle: Long = -1L

    private val userStop = AtomicBoolean(false)
    override val busy: Boolean get() = mutex.isLocked

    init {
        PLog.log("LlamaEngine init: state=${_state.value}")
    }

    suspend fun load(
        file: File,
        contextSize: Int,
        threads: Int,
        gpuOffload: Boolean = true,
        batchSize: Int = 2048,
    ) {
        if (!file.exists() || !file.canRead()) {
            _state.value = EngineState.Error("Model file not found or unreadable: ${file.name}")
            return
        }
        val err = LlamaBridge.loadError
        if (err != null) {
            _state.value = EngineState.Error("Native engine not available: ${err.message ?: err::class.java.simpleName}")
            return
        }
        try {
            mutex.withLock {
                try {
                    LlamaBridge.backendInit()
                } catch (t: Throwable) {
                    PLog.error("backendInit failed", t)
                }
                unloadInternal()
                _state.value = EngineState.Loading(file.name)
                val threadCount = threads.coerceIn(1, 8)
                val effectiveBatch = batchSize.coerceIn(128, 8192)
                // Gate GPU offload on actual native support. Cheap tablets (e.g. Rockchip P20HD)
                // report no Vulkan backend; sending gpuLayers=99 anyway segfaults llama_decode.
                val nativeSupportsGpu = try { LlamaBridge.supportsGpuOffload() } catch (_: Throwable) { false }
                runCatching { PLog.log("backend info:\n" + LlamaBridge.backendInfo()) }

                // Auto-fallback: `llama_supports_gpu_offload()` only checks whether a GPU
                // backend *registered*, not whether it actually works. On Mali-G615 the
                // Vulkan backend enumerates but fails its feature probe
                // (unresolved: vkGetPhysicalDeviceFeatures2), so a 27B load keeps dying at
                // gpuLayers=99. Strategy: attempt GPU first, and if model init fails,
                // transparently retry with gpuLayers=0 (pure CPU) so a 4B model still loads.
                val gpuLayersToTry = (if (gpuOffload && nativeSupportsGpu) listOf(99, 0) else listOf(0)).distinct()
                var h = -1L
                var lastError: String? = null
                for (layers in gpuLayersToTry) {
                    PLog.log(
                        "load: ${file.name} ctx=$contextSize batch=$effectiveBatch threads=$threadCount " +
                            "gpuRequested=$gpuOffload gpuNative=$nativeSupportsGpu → attempt gpuLayers=$layers"
                    )
                    h = withContext(dispatcher) {
                        try {
                            LlamaBridge.loadModel(
                                file.absolutePath,
                                contextSize,
                                effectiveBatch,
                                threadCount,
                                layers,
                            )
                        } catch (t: Throwable) {
                            PLog.error("loadModel exception for layers=$layers", t)
                            lastError = t.message ?: t::class.java.simpleName
                            -1L
                        }
                    }
                    if (h >= 0L) {
                        PLog.log("load: OK gpuLayers=$layers")
                        break
                    }
                    if (lastError == null) {
                        lastError = "gpuLayers=$layers failed to initialize"
                    }
                }
                if (h < 0L) {
                    _state.value = EngineState.Error("Failed to load ${file.name} ($lastError)")
                } else {
                    handle = h
                    _state.value = EngineState.Ready(file.name.removeSuffix(".gguf"), LlamaBridge.contextLength(h))
                }
            }
        } catch (t: Throwable) {
            PLog.error("load failed", t)
            _state.value = EngineState.Error("Failed to load ${file.name}: ${t.message ?: t::class.java.simpleName}")
        }
    }

    suspend fun unload() {
        mutex.withLock { unloadInternal() }
    }

    private fun unloadInternal() {
        if (handle >= 0L) {
            LlamaBridge.freeModel(handle)
            handle = -1L
        }
        _state.value = EngineState.Empty
    }

    override fun chatPrompt(messages: List<Pair<String, String>>): String? {
        val h = handle
        if (h < 0L || messages.isEmpty()) {
            PLog.log("chatPrompt: skip h=$h messages=${messages.size}")
            return null
        }
        return try {
            val result = LlamaBridge.applyChatTemplate(
                h,
                messages.map { it.first }.toTypedArray(),
                messages.map { it.second }.toTypedArray(),
            )
            PLog.log("chatPrompt: applied template, len=${result.length}")
            result.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            PLog.error("chatPrompt JNI", e)
            null
        }
    }

    override suspend fun generate(
        prompt: String,
        params: GenParams,
        onToken: (String) -> Unit,
    ): GenResult? {
        PLog.log("generate: entering, promptLen=${prompt.length} maxTokens=${params.maxTokens}")
        return mutex.withLock {
            val h = handle
            PLog.log("generate: lock acquired, h=$h state=${_state.value}")
            if (h < 0L) {
                PLog.log("generate: skip, handle not ready (state=${_state.value})")
                return@withLock null
            }
            if (prompt.isBlank()) {
                PLog.log("generate: skip blank prompt")
                return@withLock null
            }
            userStop.set(false)
            val sink = TokenSink { chunk ->
                if (userStop.get()) return@TokenSink false
                onToken(String(chunk, Charsets.UTF_8))
                true
            }
            PLog.log("generate: about to call JNI, promptLen=${prompt.length}")
            val counts = try {
                withContext(dispatcher) {
                    LlamaBridge.generate(h, prompt, params.maxTokens, params.temperature, params.topP, params.topK, params.seed, params.grammar, sink)
                }
            } catch (e: Exception) {
                PLog.error("generate JNI", e)
                null
            }
            PLog.log("generate: JNI returned, promptLen=${prompt.length} → counts=${counts?.toList()}")
            counts?.takeIf { it.size >= 2 }?.let { GenResult(it[0], it[1], userStop.get()) }
        }
    }

    override fun requestStop() {
        userStop.set(true)
        val h = handle
        if (h >= 0L) LlamaBridge.stopGeneration(h)
    }
}
