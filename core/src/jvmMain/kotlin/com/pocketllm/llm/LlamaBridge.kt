package com.pocketllm.llm

fun interface TokenSink {
    fun onToken(chunk: ByteArray): Boolean
}

object LlamaBridge {
    @Volatile
    var loadError: Throwable? = null
        private set

    init {
        try {
            val explicit = System.getenv("POCKETLLM_NATIVE_LIB")
            if (explicit != null) {
                System.load(explicit)
            } else {
                runCatching { System.loadLibrary("c++_shared") }
                runCatching { System.loadLibrary("OpenCLshim") }
                runCatching { System.loadLibrary("vkshim") }
                runCatching { System.loadLibrary("vulkan_freedreno") }
                System.loadLibrary("pocketllm")
            }
        } catch (t: Throwable) {
            loadError = t
        }
    }

    val isAvailable: Boolean
        get() = loadError == null

    fun safeSupportsGpuOffload(): Boolean {
        if (loadError != null) return false
        return try {
            supportsGpuOffload()
        } catch (_: Throwable) {
            false
        }
    }

    fun safeBackendInfo(): String {
        if (loadError != null) return "Native backend not available (${loadError?.message ?: "library load error"})"
        return try {
            backendInfo()
        } catch (t: Throwable) {
            "Backend error: ${t.message}"
        }
    }

    external fun backendInit()

    external fun backendInfo(): String
    external fun supportsGpuOffload(): Boolean
    external fun loadModel(path: String, contextSize: Int, batchSize: Int, threads: Int, gpuLayers: Int): Long
    external fun freeModel(handle: Long)
    external fun contextLength(handle: Long): Int
    external fun applyChatTemplate(handle: Long, roles: Array<String>, contents: Array<String>): String
    external fun generate(
        handle: Long,
        prompt: String,
        maxNewTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        seed: Long,
        grammar: String?,
        sink: TokenSink,
    ): IntArray?

    external fun stopGeneration(handle: Long)
}
