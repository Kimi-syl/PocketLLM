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
    val hfToken: String = "",
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
