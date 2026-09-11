package com.pocketllm.settings

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Coarse-grained performance profile that controls n_ctx / n_batch /
 * gpuOffload together. Resolved into concrete values at model load time
 * by [SettingsRepository.resolve]. The user's individual
 * [AppSettings.contextSize] / [AppSettings.gpuOffload] are ignored unless
 * the preset is [SpeedPreset.Custom].
 */
@Serializable
enum class SpeedPreset {
    /** 512 ctx, 512 batch, CPU only. Slowest but lowest RAM; for 1-2B models on 2GB devices. */
    BatterySaver,
    /** 2048 ctx, 1024 batch, GPU if available. The sane default. */
    Balanced,
    /** 1024 ctx, 2048 batch, GPU if available. Bigger batch helps GPU offload; smaller ctx speeds up prompt-eval. */
    MaxSpeed,
    /** Use the user's individual [AppSettings.contextSize] / [AppSettings.gpuOffload] as-is. */
    Custom,
}

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
        "read_file", "write_file", "run_code", "device_info",  // clipboard is opt-in: it exposes user data
    ),
    /**
     * Coarse performance profile. Resolved into concrete (contextSize, batchSize,
     * gpuOffload) at model load time. See [SpeedPreset] for what each preset means.
     */
    val speedPreset: SpeedPreset = SpeedPreset.Balanced,
    /** Only used when [speedPreset] is [SpeedPreset.Custom]: max tokens per decode batch. */
    val batchSize: Int = 2048,

    // --- Floating companion -------------------------------------------------
    /** Master switch for the floating bubble (survives leaving the app). */
    val companionEnabled: Boolean = false,
    /** Extra persona text prepended to the built-in companion prompt. */
    val companionPersona: String = "",
    /** Speak companion replies out loud using the configured TTS engine. */
    val companionTts: Boolean = false,
    /** Last bubble position, persisted so it reappears where the user left it. */
    val companionBubbleX: Int = -1,
    val companionBubbleY: Int = 400,

    // --- Companion identity & personality -----------------------------------
    /** What she calls herself in the panel header. */
    val companionName: String = "Momo",
    /** Personality preset id; see PersonalityPreset. */
    val companionStyle: String = "gentle",
    /** 0-100 traits, blended into the system prompt. */
    val companionWarmth: Int = 70,
    val companionDirectness: Int = 35,
    val companionPlayfulness: Int = 45,
    /** 0-100; 0 = terse one-liners, 100 = likes to talk. */
    val companionVerbosity: Int = 30,
    /** Bubble face. Any short string, usually one emoji. */
    val companionGlyph: String = "\uD83D\uDC31",
    /** Bubble diameter in dp. */
    val companionBubbleSize: Int = 60,
    /** Bubble opacity, 20-100 percent. */
    val companionBubbleAlpha: Int = 100,

    // --- Companion memory ---------------------------------------------------
    /** Remember durable facts about the user across conversations. */
    val companionMemoryEnabled: Boolean = true,
    /** Run the (extra) model pass that pulls facts out of the conversation. */
    val companionMemoryExtraction: Boolean = true,

    // --- Cloud entry --------------------------------------------------------
    /** Allow the companion to call a remote OpenAI-compatible endpoint. */
    val cloudEnabled: Boolean = false,
    /** Base URL including the version segment, e.g. https://api.openai.com/v1 */
    val cloudBaseUrl: String = "",
    val cloudApiKey: String = "",
    val cloudModel: String = "",
) {
    /**
     * Concrete (contextSize, batchSize, gpuOffload) for the active preset.
     * [Custom] returns the user's individual fields as-is.
     */
    fun resolve(): ResolvedSpeed = when (speedPreset) {
        SpeedPreset.BatterySaver -> ResolvedSpeed(contextSize = 512, batchSize = 512, gpuOffload = false)
        SpeedPreset.Balanced     -> ResolvedSpeed(contextSize = 2048, batchSize = 1024, gpuOffload = gpuOffload)
        SpeedPreset.MaxSpeed     -> ResolvedSpeed(contextSize = 1024, batchSize = 2048, gpuOffload = gpuOffload)
        SpeedPreset.Custom       -> ResolvedSpeed(contextSize = contextSize, batchSize = batchSize, gpuOffload = gpuOffload)
    }
}

data class ResolvedSpeed(
    val contextSize: Int,
    val batchSize: Int,
    val gpuOffload: Boolean,
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
