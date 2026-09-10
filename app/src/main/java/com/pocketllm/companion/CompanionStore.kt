package com.pocketllm.companion

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One persisted turn. Mirrors [CompanionMsg] in a serializable form. */
@Serializable
data class CompanionTurn(val fromUser: Boolean, val text: String)

/**
 * Small on-disk transcript for the companion.
 *
 * The overlay lives in a foreground service, which Android can still kill under
 * memory pressure; without this the whole conversation would vanish and she
 * would greet the user as a stranger every time.
 */
class CompanionStore(context: Context) {

    private val file = File(context.filesDir, "companion_chat.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<CompanionTurn> =
        runCatching {
            if (!file.exists()) emptyList()
            else json.decodeFromString<List<CompanionTurn>>(file.readText())
        }.getOrDefault(emptyList())

    fun save(turns: List<CompanionTurn>) {
        runCatching {
            // Keep the tail only: the file stays tiny and writes stay cheap,
            // and ancient small talk is not worth the bytes.
            file.writeText(json.encodeToString(turns.takeLast(MAX_TURNS)))
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        const val MAX_TURNS = 60
    }
}
