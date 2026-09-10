package com.pocketllm.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Minimal outbound OpenAI-compatible client.
 *
 * The Android app previously only *served* this API; the companion is the
 * first feature that calls a remote model, so this is the "cloud entry".
 */
class CloudLlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Msg(val role: String, val content: String)

    @Serializable
    private data class Req(
        val model: String,
        val messages: List<Msg>,
        val max_tokens: Int,
        val temperature: Double,
    )

    @Serializable
    private data class Choice(val message: Msg? = null)

    @Serializable
    private data class Resp(val choices: List<Choice> = emptyList())

    /**
     * @param messages role/content pairs, system prompt first.
     */
    suspend fun complete(
        messages: List<Pair<String, String>>,
        maxTokens: Int = 400,
        temperature: Double = 0.8,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = baseUrl.trimEnd('/') + "/chat/completions"
            val payload = Req(
                model = model.ifBlank { "gpt-4o-mini" },
                messages = messages.map { Msg(it.first, it.second) },
                max_tokens = maxTokens,
                temperature = temperature,
            )
            val builder = Request.Builder()
                .url(url)
                .post(json.encodeToString(payload).toRequestBody(JSON))
            if (apiKey.isNotBlank()) {
                builder.header("Authorization", "Bearer $apiKey")
            }
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // Surface the server's own message: a bare "HTTP 401" is
                    // useless when the user is debugging a key or a model name.
                    error("HTTP ${response.code}: ${body.take(300)}")
                }
                json.decodeFromString<Resp>(body)
                    .choices.firstOrNull()?.message?.content?.trim().orEmpty()
            }
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
