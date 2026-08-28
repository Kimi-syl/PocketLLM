package com.pocketllm.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class WebResult(val title: String, val url: String, val snippet: String)

object WebSearch {

    val engines = listOf(
        "duckduckgo" to "DuckDuckGo",
        "brave" to "Brave",
        "tavily" to "Tavily",
        "bing" to "Bing",
        "firecrawl" to "Firecrawl",
    )

    fun requiresKey(engine: String): Boolean = engine != "duckduckgo"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    suspend fun search(query: String, engine: String, apiKey: String?, maxResults: Int = 5): List<WebResult> =
        withContext(Dispatchers.IO) {
            when (engine) {
                "brave" -> braveSearch(query, apiKey.orEmpty(), maxResults)
                "tavily" -> tavilySearch(query, apiKey.orEmpty(), maxResults)
                "bing" -> bingSearch(query, apiKey.orEmpty(), maxResults)
                "firecrawl" -> firecrawlSearch(query, apiKey.orEmpty(), maxResults)
                else -> duckduckgoSearch(query, maxResults)
            }.take(maxResults)
        }

    private fun requestBuilder(url: String): Request.Builder =
        Request.Builder().url(url).header("Accept", "application/json")

    private fun duckduckgoSearch(query: String, maxResults: Int): List<WebResult> = runCatching {
        val url = "https://lite.duckduckgo.com/lite/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .build()
        val request = Request.Builder().url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
            .header("Accept", "text/html")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        android.util.Log.d("WebSearch", "DDG response: ${response.code}, body length: ${body.length}")
        if (body.length < 200) {
            android.util.Log.w("WebSearch", "DDG body too short: $body")
        }
        response.use {
            check(it.isSuccessful) { "HTTP ${it.code}" }
            parseDdgHtml(body, maxResults)
        }
    }.onFailure {
        android.util.Log.e("WebSearch", "DDG search failed", it)
    }.getOrDefault(emptyList())

    private fun parseDdgHtml(html: String, maxResults: Int): List<WebResult> {
        val anchorRegex = Regex("""<a[^>]*class=['"]result-link['"][^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val anchorRegex2 = Regex("""<a[^>]*href="([^"]+)"[^>]*class=['"]result-link['"][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("""class=['"]result-snippet['"][^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)

        val anchors = (anchorRegex.findAll(html) + anchorRegex2.findAll(html))
            .map { extractDdgUrl(it.groupValues[1]) to stripTags(it.groupValues[2]) }
            .filter { (url, title) ->
                title.isNotBlank() && url.isNotBlank()
            }
            .toList()
        val snippets = snippetRegex.findAll(html).map { stripTags(it.groupValues[1]) }.toList()

        return anchors.mapIndexedNotNull { index, (url, title) ->
            if (index >= maxResults) return@mapIndexedNotNull null
            WebResult(title, url, snippets.getOrElse(index) { "" })
        }
    }

    private fun extractDdgUrl(raw: String): String {
        val trimmed = raw.trim()
        // Extract the real URL from //duckduckgo.com/l/?uddg=...&rut=... FIRST
        val uddgMatch = Regex("""uddg=([^&]+)""").find(trimmed)
        if (uddgMatch != null) {
            return java.net.URLDecoder.decode(uddgMatch.groupValues[1], "UTF-8")
        }
        if (trimmed.startsWith("http")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        return trimmed
    }

    @Serializable
    private data class BraveWeb(val results: List<BraveItem> = emptyList())
    @Serializable
    private data class BraveItem(val title: String = "", val url: String = "", val description: String = "")
    @Serializable
    private data class BraveResponse(val web: BraveWeb? = null)

    private fun braveSearch(query: String, key: String, maxResults: Int): List<WebResult> = runCatching {
        check(key.isNotBlank()) { "Brave API key required" }
        val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .addQueryParameter("count", maxResults.toString())
            .build()
        val request = requestBuilder(url.toString())
            .header("X-Subscription-Token", key)
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Brave HTTP ${response.code}" }
            val parsed = json.decodeFromString<BraveResponse>(response.body?.string().orEmpty())
            parsed.web?.results?.map { WebResult(it.title, it.url, it.description) } ?: emptyList()
        }
    }.getOrDefault(emptyList())

    @Serializable
    private data class TavilyItem(val title: String = "", val url: String = "", val content: String = "")
    @Serializable
    private data class TavilyResponse(val results: List<TavilyItem> = emptyList())

    private fun tavilySearch(query: String, key: String, maxResults: Int): List<WebResult> = runCatching {
        check(key.isNotBlank()) { "Tavily API key required" }
        val body = json.encodeToString(
            TavilyRequest(key, query.trim(), maxResults)
        ).toRequestBody(jsonMedia)
        val request = Request.Builder().url("https://api.tavily.com/search").post(body).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Tavily HTTP ${response.code}" }
            val parsed = json.decodeFromString<TavilyResponse>(response.body?.string().orEmpty())
            parsed.results.map { WebResult(it.title, it.url, it.content) }
        }
    }.getOrDefault(emptyList())

    @Serializable
    private data class TavilyRequest(val api_key: String, val query: String, val max_results: Int)

    @Serializable
    private data class BingWebPages(val value: List<BingItem> = emptyList())
    @Serializable
    private data class BingItem(val name: String = "", val url: String = "", val snippet: String = "")
    @Serializable
    private data class BingResponse(val webPages: BingWebPages? = null)

    private fun bingSearch(query: String, key: String, maxResults: Int): List<WebResult> = runCatching {
        check(key.isNotBlank()) { "Bing API key required" }
        val url = "https://api.bing.microsoft.com/v7.0/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .addQueryParameter("count", maxResults.toString())
            .build()
        val request = requestBuilder(url.toString())
            .header("Ocp-Apim-Subscription-Key", key)
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Bing HTTP ${response.code}" }
            val parsed = json.decodeFromString<BingResponse>(response.body?.string().orEmpty())
            parsed.webPages?.value?.map { WebResult(it.name, it.url, it.snippet) } ?: emptyList()
        }
    }.getOrDefault(emptyList())

    @Serializable
    private data class FirecrawlItem(val title: String? = null, val url: String? = null, val description: String? = null)
    @Serializable
    private data class FirecrawlResponse(val data: List<FirecrawlItem> = emptyList())

    private fun firecrawlSearch(query: String, key: String, maxResults: Int): List<WebResult> = runCatching {
        check(key.isNotBlank()) { "Firecrawl API key required" }
        val body = json.encodeToString(FirecrawlRequest(query.trim(), maxResults)).toRequestBody(jsonMedia)
        val request = requestBuilder("https://api.firecrawl.dev/v1/search")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Firecrawl HTTP ${response.code}" }
            val parsed = json.decodeFromString<FirecrawlResponse>(response.body?.string().orEmpty())
            parsed.data.mapNotNull { item ->
                val url = item.url ?: return@mapNotNull null
                WebResult(item.title ?: url, url, item.description.orEmpty())
            }
        }
    }.getOrDefault(emptyList())

    @Serializable
    private data class FirecrawlRequest(val query: String, val limit: Int)

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
