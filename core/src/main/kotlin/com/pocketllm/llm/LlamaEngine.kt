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
    val grammar: String? = null,
)

data class GenResult(
    val promptTokens: Int,
    val generatedTokens: Int,
    val stoppedByUser: Boolean,
)

object LlamaEngine : ChatEngine {

    private val dispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "llama-engine").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val mutex = Mutex()

    private val _state = MutableStateFlow<EngineState>(EngineState.Empty)
    override val state: StateFlow<EngineState> = _state

    @Volatile
    private var handle: Long = -1L

    private val userStop = AtomicBoolean(false)
    override val busy: Boolean get() = mutex.isLocked

    init {
        PLog.log("LlamaEngine init: state=${_state.value}")
    }

    suspend fun load(file: File, contextSize: Int, threads: Int, gpuOffload: Boolean = true) {
        mutex.withLock {
            LlamaBridge.backendInit()
            unloadInternal()
            _state.value = EngineState.Loading(file.name)
            val threadCount = threads.coerceIn(1, 8)
            // Gate GPU offload on actual native support. Cheap tablets (e.g. Rockchip P20HD)
            // report no Vulkan backend; sending gpuLayers=99 anyway segfaults llama_decode.
            val nativeSupportsGpu = try { LlamaBridge.supportsGpuOffload() } catch (_: Throwable) { false }
            val effectiveGpuLayers = if (gpuOffload && nativeSupportsGpu) 99 else 0
            PLog.log(
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
            counts?.let { GenResult(it[0], it[1], userStop.get()) }
        }
    }

    override fun requestStop() {
        userStop.set(true)
        val h = handle
        if (h >= 0L) LlamaBridge.stopGeneration(h)
    }
}
