package com.pocketllm.llm

/**
 * Platform-neutral chat engine surface used by the agent loop and server
 * code. [LlamaEngine] implements this on every platform (Android + desktop
 * JVM share the JNI bridge).
 */
interface ChatEngine {
    val state: kotlinx.coroutines.flow.StateFlow<EngineState>

    fun chatPrompt(messages: List<Pair<String, String>>): String?

    suspend fun generate(
        prompt: String,
        params: GenParams = GenParams(),
        onToken: (String) -> Unit,
    ): GenResult?

    fun requestStop()

    val busy: Boolean
}
