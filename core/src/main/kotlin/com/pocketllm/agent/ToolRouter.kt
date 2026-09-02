package com.pocketllm.agent

/**
 * Zero-cost heuristic that decides whether the current user message plausibly
 * needs a tool. When it fires, AgentLoop constrains generation to a single
 * grammatically-valid tool call; when it does not, the model answers freely
 * and the existing parse path still catches any TOOL: line the model emits.
 *
 * Deliberately conservative: cues are strong signals only, and each cue is
 * gated on the corresponding tool actually being enabled.
 */
class ToolRouter {

    fun wantsTools(userMessage: String, enabledTools: Set<String>): Boolean {
        if (enabledTools.isEmpty()) return false
        val msg = userMessage.lowercase()

        if ("read_url" in enabledTools && Regex("https?://").containsMatchIn(msg)) return true

        if ("calculate" in enabledTools) {
            if (Regex("""\d\s*[+\-*/^%]\s*\d""").containsMatchIn(msg)) return true
            if ("calculate" in msg || "how much is" in msg) return true
        }

        if ("web_search" in enabledTools) {
            val cues = listOf(
                "search", "look up", "google", "latest", "news", "weather",
                "current", "right now", "price", "stock", "score", "who is",
                "what happened",
            )
            if (cues.any { it in msg }) return true
        }

        if ("datetime" in enabledTools) {
            val cues = listOf(
                "what time", "what day", "what's the date", "whats the date",
                "today's date", "todays date", "current date", "current time",
            )
            if (cues.any { it in msg }) return true
        }

        return false
    }
}
