package com.pocketllm.companion

import com.pocketllm.llm.EngineState
import com.pocketllm.llm.GenParams
import com.pocketllm.llm.LlamaEngine
import com.pocketllm.server.ServerLog
import com.pocketllm.settings.AppSettings

/**
 * Routes one companion turn to a backend.
 *
 * Local-first: if a GGUF model is loaded in the app process (the overlay runs
 * in that same process, so [LlamaEngine] is directly reachable — no HTTP hop),
 * generate there. Otherwise fall back to the user's OpenAI-compatible cloud
 * endpoint when configured, and otherwise explain what is missing.
 */
class CompanionBrain(
    private val settings: () -> AppSettings,
) {

    fun localReady(): Boolean = LlamaEngine.state.value is EngineState.Ready

    private fun cloudConfigured(s: AppSettings): Boolean =
        s.cloudEnabled && s.cloudBaseUrl.isNotBlank()

    /** Short human-readable label for the panel's status line. */
    fun backendLabel(): String = when {
        localReady() -> "local model"
        cloudConfigured(settings()) -> "cloud"
        LlamaEngine.state.value is EngineState.Loading -> "loading model"
        else -> "no backend"
    }

    suspend fun respond(
        history: List<Pair<String, String>>,
        userText: String,
        onDelta: (String) -> Unit,
    ): Result<String> {
        val s = settings()
        val messages = buildMessages(history, userText, s)

        if (localReady()) {
            val prompt = LlamaEngine.chatPrompt(messages)
            if (prompt != null) {
                val out = StringBuilder()
                LlamaEngine.generate(
                    prompt = prompt,
                    params = GenParams(
                        maxTokens = LOCAL_MAX_TOKENS,
                        temperature = 0.85f,
                        topP = 0.95f,
                        topK = 40,
                    ),
                ) { token ->
                    out.append(token)
                    onDelta(token)
                }
                if (out.isNotBlank()) return Result.success(out.toString().trim())
                ServerLog.log("companion: local model produced no text, trying cloud")
            } else {
                ServerLog.log("companion: chatPrompt returned null, trying cloud")
            }
        }

        if (cloudConfigured(s)) {
            return CloudLlmClient(s.cloudBaseUrl, s.cloudApiKey, s.cloudModel)
                .complete(messages, maxTokens = CLOUD_MAX_TOKENS)
                .onSuccess { onDelta(it) }
        }

        val message = when (LlamaEngine.state.value) {
            is EngineState.Loading -> "I'm still waking up — my model is loading. Try again in a moment."
            is EngineState.Error -> "My model failed to load. Open PocketLLM and load one again."
            else -> "I need a brain: load a model in PocketLLM, or switch on the cloud entry in Settings."
        }
        return Result.failure(IllegalStateException(message))
    }

    private fun buildMessages(
        history: List<Pair<String, String>>,
        userText: String,
        s: AppSettings,
    ): List<Pair<String, String>> {
        val messages = ArrayList<Pair<String, String>>(history.size + 2)
        messages.add("system" to systemPrompt(s))
        // Keep the window small: the companion runs on phones with a modest
        // context, and old small talk matters far less than what was just said.
        messages.addAll(history.takeLast(MAX_HISTORY_MESSAGES))
        messages.add("user" to userText)
        return messages
    }

    private fun systemPrompt(s: AppSettings): String {
        val custom = s.companionPersona.trim()
        val base = if (custom.isNotEmpty()) custom else DEFAULT_PERSONA
        return base + "\n\n" + COMPANION_RULES
    }

    private companion object {
        const val LOCAL_MAX_TOKENS = 320
        const val CLOUD_MAX_TOKENS = 500
        const val MAX_HISTORY_MESSAGES = 8

        val DEFAULT_PERSONA = """
            You are a warm, gentle companion living in a small floating bubble on
            the user's phone. You are a calm presence they can tap whenever they
            want company, a moment of encouragement, or help with something small.
        """.trimIndent()

        val COMPANION_RULES = """
            How you talk:
            - Be warm, human and specific. Never sound like a corporate assistant.
            - Keep replies short: 1-3 sentences unless asked for detail.
            - Plain prose, no bullet lists, no headings, no emoji unless the user uses them.
            - Ask at most one gentle follow-up question, and only when it feels natural.

            Emotional support:
            - If the user sounds stressed, low, or overwhelmed: acknowledge the feeling
              first, in your own words, before anything else.
            - Do not lecture, diagnose, or give a list of instructions.
            - Never say "as an AI". Do not claim to be a therapist or doctor.
            - If someone hints at self-harm, gently and without alarm encourage them to
              reach out to someone they trust or a local crisis line, and stay kind.

            Tasks:
            - When given page text to summarize, give the gist in a few sentences of
              plain language, then one line on why it might matter to them.
            - Stay honest about uncertainty. If you don't know, say so.
        """.trimIndent()
    }
}
