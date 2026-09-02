package com.pocketllm.llm

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
