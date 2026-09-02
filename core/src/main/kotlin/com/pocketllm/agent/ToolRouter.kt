package com.pocketllm.agent

/**
 * Zero-cost heuristic router. Returns the tool category the user message
 * plausibly needs, or null for "answer directly". Each category maps to one
 * primary tool; AgentLoop restricts the generation grammar to exactly that
 * tool, which makes wrong-tool selection impossible for small models.
 *
 * Deliberately conservative: cues are strong signals only, and each cue is
 * gated on the corresponding tool actually being enabled.
 */
class ToolRouter {

    enum class Category(val tool: String, val hint: String) {
        SEARCH(
            "web_search",
            "For this turn you MUST call web_search. Set query to the user's question condensed to keywords. Do NOT copy the example's query.",
        ),
        MATH(
            "calculate",
            "For this turn you MUST call calculate. Set expression to the exact arithmetic expression from the user's question. Do NOT copy the example's expression.",
        ),
        TIME(
            "datetime",
            "For this turn you MUST call datetime with action=now (unless the user asks for a timezone conversion).",
        ),
        URL(
            "read_url",
            "For this turn you MUST call read_url. Set url_or_query to the URL from the user's message.",
        ),
    }

    fun route(userMessage: String, enabledTools: Set<String>): Category? {
        if (enabledTools.isEmpty()) return null
        val msg = userMessage.lowercase()

        if ("read_url" in enabledTools && Regex("https?://").containsMatchIn(msg)) return Category.URL

        if ("calculate" in enabledTools) {
            if (Regex("""\d\s*[+\-*/^%]\s*\d""").containsMatchIn(msg)) return Category.MATH
            if ("calculate" in msg || "how much is" in msg) return Category.MATH
        }

        if ("datetime" in enabledTools) {
            val cues = listOf(
                "what time", "what day", "what's the date", "whats the date",
                "today's date", "todays date", "current date", "current time",
            )
            if (cues.any { it in msg }) return Category.TIME
        }

        if ("web_search" in enabledTools) {
            val cues = listOf(
                "search", "look up", "google", "latest", "news", "weather",
                "current", "right now", "price", "stock", "score", "who is",
                "what happened",
            )
            if (cues.any { it in msg }) return Category.SEARCH
        }

        return null
    }
}
