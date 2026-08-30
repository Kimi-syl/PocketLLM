package com.pocketllm.settings

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AppSettings(
    val port: Int = 8080,
    val requireApiKey: Boolean = true,
    val contextSize: Int = 2048,
    val maxGenerationTokens: Int = 1024,
    val hfToken: String = "",
    val themeMode: String = "system",
    val dynamicColor: Boolean = true,
    val startupPrompt: String = "",
    val httpsEnabled: Boolean = false,
    val searchEngine: String = "duckduckgo",
    val braveKey: String = "",
    val tavilyKey: String = "",
    val bingKey: String = "",
    val firecrawlKey: String = "",
    val ttsAutoSpeak: Boolean = false,
    val ttsEngine: String = "system",
    val gpuOffload: Boolean = true,
    val agentEnabled: Boolean = false,
    val enabledTools: Set<String> = setOf(
        "web_search", "read_url", "calculate", "datetime",
        "read_file", "write_file", "run_code", "clipboard", "device_info",
    ),
)

class SettingsRepository(context: Context) {

    private val file = File(context.filesDir, "app_settings.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile
    private var cached: AppSettings? = null

    fun current(): AppSettings {
        cached?.let { return it }
        val loaded = if (file.exists()) {
            runCatching { json.decodeFromString<AppSettings>(file.readText()) }.getOrDefault(AppSettings())
        } else AppSettings()
        cached = loaded
        return loaded
    }

    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = mutex.withLock {
        val next = transform(current())
        file.writeText(json.encodeToString(next))
        cached = next
        next
    }
}
