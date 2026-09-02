package com.pocketllm.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Fetches a web page and returns readable text. Accepts either a full URL or
 * a free-form query. If the input is not a URL, we run it through
 * [webSearchFallback] (typically a WebSearchTool call) and open the first hit.
 *
 * Extraction: Jsoup-driven. We drop noisy tags (script, style, nav, footer,
 * aside) and pull text from <p>, headings, list items, blockquotes, and <pre>
 * blocks. Output is truncated to `max_chars` so we never overflow the
 * model's context window.
 */
class WebFetchTool(
    private val webSearchFallback: suspend (String) -> String?,
) : AgentTool {
    override val name = "read_url"
    override val displayName = "Read URL"
    override val description = "Fetch a web page and return its readable text. Accepts a full https:// URL, or a plain query if you don't have the exact link."
    override val parameters = listOf(
        ToolParam(
            name = "url_or_query",
            type = "string",
            description = "Either a full https:// URL or a search-style query.",
        ),
        ToolParam(
            name = "max_chars",
            type = "integer",
            description = "Maximum characters to return (default 8000, max 32000).",
            required = false,
        ),
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val input = (args["url_or_query"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (input.isBlank()) return ToolResult.Error("url_or_query must not be empty")
        val maxChars = ((args["max_chars"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 8_000)
            .coerceIn(500, 32_000)

        val url = resolveUrl(input)
            ?: return ToolResult.Error("Not a URL and no search results found for \"$input\"")

        val html = runCatching { fetch(url) }
            .getOrElse { return ToolResult.Error("Fetch failed: ${it.message}") }
        if (html.length < 100) return ToolResult.Error("Page too small (${html.length} bytes)")

        val doc = runCatching { Jsoup.parse(html, url) }.getOrNull()
            ?: return ToolResult.Error("HTML parse failed")

        val title = doc.title().ifBlank { url }
        val text = extractReadable(doc, maxChars)
        if (text.isBlank()) return ToolResult.Error("Page returned no readable text")

        return ToolResult.Content(
            title = title,
            body = text,
            source = url,
            summary = "$title — ${text.length} chars from $url",
        )
    }

    override fun summarize(args: Map<String, JsonElement>): String {
        val input = (args["url_or_query"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        return if (URL_REGEX.matcher(input).matches()) "fetch: $input" else "fetch (search): $input"
    }

    private suspend fun resolveUrl(input: String): String? {
        if (URL_REGEX.matcher(input).matches()) return input
        return webSearchFallback(input)
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            return response.body?.string().orEmpty()
        }
    }

    private fun extractReadable(doc: Document, maxChars: Int): String {
        doc.select("script, style, noscript, iframe, svg, header nav, footer nav, aside, form, button").remove()

        val main = doc.select("main, article, [role=main], #content, .content, .post, .article").firstOrNull()
            ?: doc.body() ?: return ""

        val paragraphs = main.select("p, h1, h2, h3, h4, h5, h6, li, blockquote, pre")
        val out = StringBuilder()
        for (el in paragraphs) {
            val text = el.text().trim()
            if (text.isBlank()) continue
            val tag = el.tagName()
            val prefix = when (tag) {
                "h1" -> "\n# "
                "h2" -> "\n## "
                "h3" -> "\n### "
                "h4" -> "\n#### "
                "li" -> "- "
                "blockquote" -> "> "
                "pre" -> "\n```\n"
                else -> ""
            }
            val suffix = if (tag == "pre") "\n```\n" else ""
            out.append(prefix).append(text).append(suffix).append("\n")
            if (out.length >= maxChars) {
                out.append("\n[truncated]")
                break
            }
        }
        return out.toString().trim()
    }

    companion object {
        private val URL_REGEX = Pattern.compile("^https?://[^\\s]+$", Pattern.CASE_INSENSITIVE)
    }
}
