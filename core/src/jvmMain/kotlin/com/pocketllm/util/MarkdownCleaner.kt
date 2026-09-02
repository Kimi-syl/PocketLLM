package com.pocketllm.util

/**
 * Strips Markdown formatting so the system TTS engine reads natural prose
 * instead of literally saying "asterisk asterisk" or "hashtag header".
 */
object MarkdownCleaner {
    fun clean(input: String): String {
        if (input.isBlank()) return input
        var text = input

        // Fenced code blocks → skip entirely (TTS would just read code characters).
        text = text.replace(Regex("```[\\s\\S]*?```"), " ")

        // Inline code → keep the content, drop the backticks.
        text = text.replace(Regex("`([^`]+)`"), "$1")

        // Images: ![alt](url) → "alt" (or drop if no alt)
        text = text.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")

        // Links: [text](url) → "text"
        text = text.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")

        // Bold: **text** or __text__ → text
        text = text.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        text = text.replace(Regex("__([^_]+)__"), "$1")

        // Italic: *text* or _text_ → text (only single asterisks, not list markers)
        text = text.replace(Regex("(?<![*\\w])\\*([^*\\n]+)\\*(?!\\w)"), "$1")
        text = text.replace(Regex("(?<![_\\w])_([^_\\n]+)_(?!\\w)"), "$1")

        // Strikethrough: ~~text~~ → text
        text = text.replace(Regex("~~([^~]+)~~"), "$1")

        // Headers at line start: #, ##, ###, etc. → drop the hashes
        text = text.replace(Regex("(?m)^#{1,6}\\s+"), "")

        // Blockquote markers: > → drop
        text = text.replace(Regex("(?m)^\\s*>\\s?"), "")

        // Unordered list markers at line start: -, *, + → drop
        text = text.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")

        // Ordered list markers at line start: 1., 2., etc. → drop
        text = text.replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")

        // Table pipes: | → space, separator lines (---) → drop
        text = text.replace(Regex("(?m)^\\s*[-:]{3,}\\s*$"), "")
        text = text.replace(Regex("\\|"), " ")

        // Horizontal rules (---, ***, ___) → blank
        text = text.replace(Regex("(?m)^\\s*([-*_])\\s*\\1\\s*\\1[-*_\\s]*$"), "")

        // HTML tags → drop
        text = text.replace(Regex("<[^>]+>"), "")

        // LaTeX inline math: $x$ → drop the dollar signs
        text = text.replace(Regex("\\$+([^$]+)\\$+"), "$1")

        // Collapse multiple newlines into single pauses (period) for TTS flow.
        text = text.replace(Regex("\\n{3,}"), ".\n\n")
        text = text.replace(Regex("\\n\\n+"), ". ")

        // Remaining stray markdown chars
        text = text.replace(Regex("(?m)^\\s*[#>`~]+\\s*"), "")

        return text.trim()
    }
}
