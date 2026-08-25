package com.pocketllm.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class WebResult(val title: String, val url: String, val snippet: String)

object WebSearch {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, maxResults: Int = 5): List<WebResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://lite.duckduckgo.com/lite/".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query.trim())
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
                    )
                    .build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Search failed: HTTP ${response.code}" }
                    val html = response.body?.string().orEmpty()
                    parse(html, maxResults)
                }
            }.getOrDefault(emptyList())
        }

    private fun parse(html: String, maxResults: Int): List<WebResult> {
        val anchorRegex = Regex("""<a[^>]*href="(http[^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("""class=["']result-snippet["'][^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)

        val anchors = anchorRegex.findAll(html)
            .map { stripTags(it.groupValues[2]) to cleanUrl(it.groupValues[1]) }
            .filter { (title, url) ->
                title.isNotBlank() && !url.contains("duckduckgo.com") && !url.startsWith("https://duckduckgo")
            }
            .toList()

        val snippets = snippetRegex.findAll(html).map { stripTags(it.groupValues[1]) }.toList()

        return anchors.mapIndexedNotNull { index, (title, url) ->
            if (index >= maxResults) return@mapIndexedNotNull null
            WebResult(title, url, snippets.getOrElse(index) { "" })
        }
    }

    private fun cleanUrl(raw: String): String = raw.trim()

    private fun stripTags(raw: String): String {
        val text = raw.replace(Regex("<[^>]*>"), "")
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
