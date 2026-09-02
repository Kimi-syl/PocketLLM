package com.pocketllm.hf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

@Serializable
data class HfSearchResult(
    @SerialName("id") val id: String? = null,
    @SerialName("modelId") val modelId: String? = null,
    @SerialName("downloads") val downloads: Long = 0L,
    @SerialName("likes") val likes: Int = 0,
) {
    val repoId: String get() = modelId ?: id ?: ""
}

@Serializable
data class HfLfsInfo(@SerialName("size") val size: Long = 0L)

@Serializable
data class HfTreeEntry(
    @SerialName("type") val type: String = "file",
    @SerialName("path") val path: String = "",
    @SerialName("size") val size: Long = 0L,
    @SerialName("lfs") val lfs: HfLfsInfo? = null,
) {
    val realSize: Long get() = lfs?.size ?: size
}

class HuggingFaceClient(private val tokenProvider: () -> String? = { null }) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String, limit: Int = 25): List<HfSearchResult> = withContext(Dispatchers.IO) {
        val url = "https://huggingface.co/api/models".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("filter", "gguf")
            .addQueryParameter("sort", "downloads")
            .addQueryParameter("direction", "-1")
            .addQueryParameter("limit", limit.toString())
            .build()
        val request = authorize(Request.Builder().url(url)).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Search failed: HTTP ${response.code}" }
            val body = response.body?.string() ?: error("Empty search response")
            json.decodeFromString<List<HfSearchResult>>(body)
        }
    }

    suspend fun listGgufFiles(repoId: String, revision: String = "main"): List<HfTreeEntry> =
        withContext(Dispatchers.IO) {
            val url = "https://huggingface.co/api/models/$repoId/tree/$revision?recursive=false"
            val request = authorize(Request.Builder().url(url)).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Could not list files: HTTP ${response.code}" }
                val body = response.body?.string() ?: error("Empty file listing")
                json.decodeFromString<List<HfTreeEntry>>(body)
                    .filter { it.type == "file" && it.path.endsWith(".gguf", ignoreCase = true) }
                    .sortedBy { it.realSize }
            }
        }

    fun downloadUrl(repoId: String, path: String, revision: String = "main"): String =
        "https://huggingface.co/$repoId/resolve/$revision/${path.trimStart('/')}"

    companion object {
        fun normalizeModelUrl(raw: String): String =
            raw.trim().replace("/blob/", "/resolve/")
    }

    private fun authorize(builder: Request.Builder): Request.Builder {
        tokenProvider()?.takeIf { it.isNotBlank() }?.let { token ->
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }
}
