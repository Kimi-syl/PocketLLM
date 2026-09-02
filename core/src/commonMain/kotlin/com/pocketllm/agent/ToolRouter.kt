package com.pocketllm.agent

/**
 * Keyword-driven tool router. Scans the user message for trigger keywords
 * (word-boundary matched) and returns the tool category whose keyword
 * appears EARLIEST in the message, so conflicting cues resolve by what the
 * user actually asked for first. AgentLoop restricts the generation grammar
 * to exactly that category's tools.
 */
class ToolRouter {

    enum class Category(val tools: List<String>, val hint: String) {
        URL(
            listOf("read_url"),
            "For this turn you MUST call read_url. Set url_or_query to the URL from the user's message.",
        ),
        MATH(
            listOf("calculate"),
            "For this turn you MUST call calculate. Set expression to the exact arithmetic expression from the user's question. Do NOT copy the example's expression.",
        ),
        TIME(
            listOf("datetime"),
            "For this turn you MUST call datetime with action=now (unless the user asks for a timezone conversion).",
        ),
        DEVICE(
            listOf("device_info"),
            "For this turn you MUST call device_info with action matching the user's question.",
        ),
        CLIPBOARD(
            listOf("clipboard"),
            "For this turn you MUST call clipboard (no arguments).",
        ),
        CODE(
            listOf("run_code", "write_file"),
            "For this turn you MUST use run_code to execute code (a shell command; for Python a python3 -c one-liner works) or write_file to save code to a file first. Write the actual code the user asked for — never invent results.",
        ),
        SEARCH(
            listOf("web_search"),
            "For this turn you MUST call web_search. Set query to the user's question condensed to keywords. Do NOT copy the example's query.",
        ),
        FILE(
            listOf("write_file"),
            "For this turn you MUST call write_file. Take path and content from the user's request.",
        ),
    }

    fun route(userMessage: String, enabledTools: Set<String>): Category? {
        if (enabledTools.isEmpty()) return null
        val msg = userMessage.lowercase()
        val hits = mutableListOf<Pair<Int, Category>>()

        fun hit(patterns: List<String>, category: Category) {
            if (category.tools.none { it in enabledTools }) return
            var best = Int.MAX_VALUE
            for (pattern in patterns) {
                val m = Regex(pattern).find(msg)
                if (m != null && m.range.first < best) best = m.range.first
            }
            if (best != Int.MAX_VALUE) hits.add(best to category)
        }

        hit(listOf("""https?://""", """\burl\b"""), Category.URL)
        hit(
            listOf("""\d\s*[+\-*/^%]\s*\d""", """\bcalculate\b""", """\bcompute\b""", """\bhow much is\b"""),
            Category.MATH,
        )
        hit(
            listOf(
                """\bwhat time\b""", """\bwhat day\b""", """\b(date|time) (is it|today|now|currently)\b""",
                """\btoday's date\b""", """\btodays date\b""", """\bcurrent (date|time)\b""",
            ),
            Category.TIME,
        )
        hit(
            listOf("""\bdevice info\b""", """\bmy (phone|device|tablet)\b""", """\bbattery\b""", """\bstorage space\b"""),
            Category.DEVICE,
        )
        hit(listOf("""\bclipboard\b"""), Category.CLIPBOARD)
        hit(
            listOf("""\bcode\b""", """\bpython\b""", """\brun\b""", """\bexecute\b""", """\bscript\b""", """\bprogram\b""", """\bcompile\b"""),
            Category.CODE,
        )
        hit(
            listOf(
                """\bsearch\b""", """\blook up\b""", """\bgoogle\b""", """\blatest\b""", """\bnews\b""",
                """\bweather\b""", """\bcurrent\b""", """\bright now\b""", """\bprice\b""", """\bstock\b""",
                """\bscore\b""", """\bwho is\b""", """\bwhat happened\b""", """\bweb\b""",
            ),
            Category.SEARCH,
        )
        hit(
            listOf("""\bwrite (a |the )?file\b""", """\bsave (it |this |that )?(to|as) (a )?file\b""", """\bcreate (a |the )?file\b"""),
            Category.FILE,
        )

        return hits.minByOrNull { it.first }?.second
    }
}
