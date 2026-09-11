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
    private val memory: () -> CompanionMemory?,
    /** Optional recent-mood hint, so she can be gentler after a hard week. */
    private val mood: () -> MoodJournalStore? = { null },
) {

    fun localReady(): Boolean = LlamaEngine.state.value is EngineState.Ready

    private fun cloudConfigured(s: AppSettings): Boolean =
        s.cloudEnabled && s.cloudBaseUrl.isNotBlank()

    fun anyBackendReady(): Boolean = localReady() || cloudConfigured(settings())

    /**
     * Ask the local model to stop mid-reply.
     *
     * Upstream cuts the generation short and returns what it had produced, so
     * the partial answer survives. A cloud stream cannot be interrupted this
     * way; the service settles that turn on a timer instead.
     */
    fun stop() {
        runCatching { LlamaEngine.requestStop() }
    }

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
        grounding: String? = null,
        onDelta: (String) -> Unit,
    ): Result<String> {
        val s = settings()
        val messages = buildMessages(history, userText, s, grounding)

        if (localReady()) {
            val prompt = LlamaEngine.chatPrompt(messages)
            if (prompt != null) {
                val out = StringBuilder()
                LlamaEngine.generate(
                    prompt = prompt,
                    params = GenParams(
                        maxTokens = localMaxTokens(s),
                        temperature = temperatureFor(s),
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
                .complete(messages, maxTokens = CLOUD_MAX_TOKENS, temperature = temperatureFor(s).toDouble())
                .onSuccess { onDelta(it) }
        }

        val message = when (LlamaEngine.state.value) {
            is EngineState.Loading -> "I'm still waking up — my model is loading. Try again in a moment."
            is EngineState.Error -> "My model failed to load. Open PocketLLM and load one again."
            else -> "I need a brain: load a model in PocketLLM, or switch on the cloud entry in Settings."
        }
        return Result.failure(IllegalStateException(message))
    }

    /**
     * Pulls durable facts out of an exchange. A separate, deliberately tiny
     * pass — asking the conversational model to both chat and self-report is
     * unreliable, and this way a failure costs nothing visible to the user.
     *
     * [known] is handed to the model so it does not spend its few output tokens
     * restating facts already stored. Dedup would catch an exact repeat, but a
     * paraphrase would slip through and slowly fill memory with noise.
     */
    suspend fun extractFacts(exchange: String, known: List<String> = emptyList()): List<String> {
        val s = settings()
        if (!s.companionMemoryEnabled || !s.companionMemoryExtraction) return emptyList()
        if (!anyBackendReady()) return emptyList()
        val system = if (known.isEmpty()) EXTRACTOR_PROMPT else {
            EXTRACTOR_PROMPT + "\n\nAlready known — do NOT repeat these or rephrase them:\n" +
                known.take(KNOWN_FACTS_IN_PROMPT).joinToString("\n") { "- $it" }
        }
        val messages = listOf(
            "system" to system,
            "user" to exchange.take(EXTRACT_MAX_CHARS),
        )
        val raw = when {
            localReady() -> {
                val prompt = LlamaEngine.chatPrompt(messages) ?: return emptyList()
                val out = StringBuilder()
                LlamaEngine.generate(prompt, GenParams(maxTokens = 140, temperature = 0.1f, topP = 0.9f, topK = 20)) { out.append(it) }
                out.toString()
            }
            else -> CloudLlmClient(s.cloudBaseUrl, s.cloudApiKey, s.cloudModel)
                .complete(messages, maxTokens = 140, temperature = 0.1)
                .getOrNull().orEmpty()
        }
        return parseFacts(raw)
    }

    private fun parseFacts(raw: String): List<String> {
        if (raw.isBlank() || raw.trim().equals("NONE", ignoreCase = true)) return emptyList()
        return raw.lines()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotEmpty() && !it.equals("NONE", ignoreCase = true) && it.length <= 200 }
            .take(5)
    }

    private fun buildMessages(
        history: List<Pair<String, String>>,
        userText: String,
        s: AppSettings,
        grounding: String? = null,
    ): List<Pair<String, String>> {
        // Only the recent window is searched for relevant memories, so a fact
        // comes up because it matches what is being discussed right now.
        val recent = (history.takeLast(4).joinToString(" ") { it.second } + " " + userText)
        val memoryBlock = if (s.companionMemoryEnabled) memory()?.promptBlock(recent).orEmpty() else ""
        val system = buildString {
            append(CompanionPersonas.systemPrompt(s, memoryBlock))
            // Understated and last, so it colours the tone rather than becoming
            // something she talks about.
            val moodHint = runCatching { mood()?.promptHint() }.getOrNull().orEmpty()
            if (moodHint.isNotBlank()) {
                append("\n\n")
                append(moodHint)
            }
            // Grounding goes last so it sits closest to the question, and only
            // for this turn — it must never enter the stored transcript.
            if (!grounding.isNullOrBlank()) {
                append("\n\n")
                append(grounding)
            }
        }
        return buildList {
            add("system" to system)
            // Keep the window small: phones have a modest context, and old small
            // talk matters far less than what was just said.
            addAll(history.takeLast(MAX_HISTORY_MESSAGES))
            add("user" to userText)
        }
    }

    /** Longer replies are offered, but the prompt still asks for brevity. */
    private fun localMaxTokens(s: AppSettings): Int = when {
        s.companionVerbosity >= 67 -> 480
        s.companionVerbosity < 34 -> 220
        else -> LOCAL_MAX_TOKENS
    }

    /** A touch more warmth/randomness for playful personalities, less for direct ones. */
    private fun temperatureFor(s: AppSettings): Float = when {
        s.companionPlayfulness >= 67 -> 0.95f
        s.companionDirectness >= 67 -> 0.65f
        else -> 0.85f
    }

    private companion object {
        const val LOCAL_MAX_TOKENS = 320
        const val CLOUD_MAX_TOKENS = 500
        const val MAX_HISTORY_MESSAGES = 8
        const val EXTRACT_MAX_CHARS = 1800
        /**
         * How many known facts to show the extractor. Capped because on a phone
         * -sized context the exchange itself matters more than a long inventory.
         */
        private const val KNOWN_FACTS_IN_PROMPT = 40

        val EXTRACTOR_PROMPT = """
            You extract durable facts about the USER from a conversation.

            Rules:
            - Only facts likely to still matter weeks from now (name, people, pets,
              work, studies, location, ongoing situations, strong preferences).
            - Never store moods, one-off questions, or anything about the assistant.
            - Write each fact as a short third-person sentence starting with "They".
            - At most 3 facts. Output one per line, no numbering, no commentary.
            - If there is nothing durable, output exactly: NONE

            Examples:
            They have a cat called Momo.
            They are studying for a law degree.
            They dislike being phoned unexpectedly.
        """.trimIndent()
    }
}
