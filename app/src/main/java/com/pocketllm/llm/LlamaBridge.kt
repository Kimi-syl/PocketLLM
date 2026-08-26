package com.pocketllm.llm

fun interface TokenSink {
    fun onToken(chunk: ByteArray): Boolean
}

object LlamaBridge {
    init {
        System.loadLibrary("pocketllm")
    }

    external fun backendInit()
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
        sink: TokenSink,
    ): IntArray?

    external fun stopGeneration(handle: Long)
}
