package com.pocketllm.llm

fun interface TokenSink {
    fun onToken(chunk: ByteArray): Boolean
}

object LlamaBridge {
    init {
        val explicit = System.getenv("POCKETLLM_NATIVE_LIB")
        if (explicit != null) System.load(explicit) else System.loadLibrary("pocketllm")
    }

    external fun backendInit()
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
