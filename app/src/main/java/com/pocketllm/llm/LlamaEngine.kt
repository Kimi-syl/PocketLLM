package com.pocketllm.llm

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed interface EngineState {
    data object Empty : EngineState
    data class Loading(val fileName: String) : EngineState
    data class Ready(val modelName: String, val contextSize: Int) : EngineState
    data class Error(val message: String) : EngineState
}

data class GenParams(
    val maxTokens: Int = 512,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val seed: Long = -1L,
)

data class GenResult(
    val promptTokens: Int,
    val generatedTokens: Int,
    val stoppedByUser: Boolean,
)

object LlamaEngine {

    private val dispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "llama-engine") }.asCoroutineDispatcher()
    private val mutex = Mutex()

    private val _state = MutableStateFlow<EngineState>(EngineState.Empty)
    val state: StateFlow<EngineState> = _state

    @Volatile
    private var handle: Long = -1L

    private val userStop = AtomicBoolean(false)
    val busy: Boolean get() = mutex.isLocked

    init {
        LlamaBridge.backendInit()
        com.pocketllm.server.ServerLog.log("LlamaEngine init: state=${_state.value}")
    }

    suspend fun load(file: File, contextSize: Int, threads: Int, gpuOffload: Boolean = true) {
        mutex.withLock {
            unloadInternal()
            _state.value = EngineState.Loading(file.name)
            val threadCount = threads.coerceIn(1, 8)
            // Gate GPU offload on actual native support. Cheap tablets (e.g. Rockchip P20HD)
            // report no Vulkan backend; sending gpuLayers=99 anyway segfaults llama_decode.
            val nativeSupportsGpu = try { LlamaBridge.supportsGpuOffload() } catch (_: Throwable) { false }
            val effectiveGpuLayers = if (gpuOffload && nativeSupportsGpu) 99 else 0
            com.pocketllm.server.ServerLog.log(
                "load: ${file.name} ctx=$contextSize threads=$threadCount gpuRequested=$gpuOffload gpuNative=$nativeSupportsGpu → gpuLayers=$effectiveGpuLayers"
            )
            val h = withContext(dispatcher) {
                LlamaBridge.loadModel(
                    file.absolutePath,
                    contextSize,
                    2048,
                    threadCount,
                    effectiveGpuLayers,
                )
            }
            if (h < 0L) {
                _state.value = EngineState.Error("Failed to load ${file.name}")
            } else {
                handle = h
                _state.value = EngineState.Ready(file.name.removeSuffix(".gguf"), LlamaBridge.contextLength(h))
            }
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

    fun chatPrompt(messages: List<Pair<String, String>>): String? {
        val h = handle
        if (h < 0L || messages.isEmpty()) {
            com.pocketllm.server.ServerLog.log("chatPrompt: skip h=$h messages=${messages.size}")
            return null
        }
        return try {
            val result = LlamaBridge.applyChatTemplate(
                h,
                messages.map { it.first }.toTypedArray(),
                messages.map { it.second }.toTypedArray(),
            )
            com.pocketllm.server.ServerLog.log("chatPrompt: applied template, len=${result.length}")
            result.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            com.pocketllm.server.ServerLog.error("chatPrompt JNI", e)
            null
        }
    }

    suspend fun generate(
        prompt: String,
        params: GenParams = GenParams(),
        onToken: (String) -> Unit,
    ): GenResult? {
        com.pocketllm.server.ServerLog.log("generate: entering, promptLen=${prompt.length} maxTokens=${params.maxTokens}")
        return mutex.withLock {
            val h = handle
            com.pocketllm.server.ServerLog.log("generate: lock acquired, h=$h state=${_state.value}")
            if (h < 0L) {
                com.pocketllm.server.ServerLog.log("generate: skip, handle not ready (state=${_state.value})")
                return@withLock null
            }
            if (prompt.isBlank()) {
                com.pocketllm.server.ServerLog.log("generate: skip blank prompt")
                return@withLock null
            }
            userStop.set(false)
            val sink = TokenSink { chunk ->
                if (userStop.get()) return@TokenSink false
                onToken(String(chunk, Charsets.UTF_8))
                true
            }
            com.pocketllm.server.ServerLog.log("generate: about to call JNI, promptLen=${prompt.length}")
            val counts = try {
                withContext(dispatcher) {
                    LlamaBridge.generate(h, prompt, params.maxTokens, params.temperature, params.topP, params.topK, params.seed, sink)
                }
            } catch (e: Exception) {
                com.pocketllm.server.ServerLog.error("generate JNI", e)
                null
            }
            com.pocketllm.server.ServerLog.log("generate: JNI returned, promptLen=${prompt.length} → counts=${counts?.toList()}")
            counts?.let { GenResult(it[0], it[1], userStop.get()) }
        }
    }

    fun requestStop() {
        userStop.set(true)
        val h = handle
        if (h >= 0L) LlamaBridge.stopGeneration(h)
    }
}
