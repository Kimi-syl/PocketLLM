package com.pocketllm.sessions

import android.content.Context
import com.pocketllm.agent.ToolCallUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * One persisted chat. A session owns a chronological list of [SessionMessage]s
 * and the metadata needed to re-render the UI.
 */
@Serializable
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<SessionMessage> = emptyList(),
) {
    val preview: String
        get() = messages.lastOrNull { it.role == "user" }?.content?.lineSequence()?.firstOrNull()
            ?: messages.firstOrNull()?.content?.lineSequence()?.firstOrNull()
            ?: "Empty chat"
}

/**
 * Persisted form of a chat message. Mirrors [com.pocketllm.ChatUiMessage] but
 * is a separate type because kotlinx.serialization is fussy about Compose-tinged
 * defaults and we want explicit fields for metrics.
 */
@Serializable
data class SessionMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0,
    val timeToFirstTokenMs: Long? = null,
    val tokensPerSecond: Float? = null,
    val totalDurationMs: Long = 0,
    val toolCalls: List<PersistedToolCall> = emptyList(),
)

@Serializable
data class PersistedToolCall(
    val id: String,
    val name: String,
    val displayName: String,
    val arguments: Map<String, String> = emptyMap(),
    val resultSummary: String = "",
    val resultDetail: String = "",
) {
    companion object {
        fun fromUi(ui: ToolCallUi) = PersistedToolCall(
            id = ui.id,
            name = ui.name,
            displayName = ui.displayName,
            arguments = ui.arguments,
            resultSummary = ui.resultSummary,
            resultDetail = ui.resultDetail,
        )
    }
}

class ChatSessionRepository(context: Context) {

    private val dir: File = File(context.filesDir, "sessions").also { it.mkdirs() }
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()

    suspend fun list(): List<ChatSession> = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                .orEmpty()
                .mapNotNull { f ->
                    runCatching { json.decodeFromString<ChatSession>(f.readText()) }.getOrNull()
                }
                .sortedByDescending { it.updatedAt }
        }
    }

    suspend fun get(id: String): ChatSession? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = File(dir, "$id.json")
            if (file.exists()) {
                runCatching { json.decodeFromString<ChatSession>(file.readText()) }.getOrNull()
            } else null
        }
    }

    suspend fun save(session: ChatSession) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = session.copy(updatedAt = System.currentTimeMillis())
            File(dir, "${updated.id}.json").writeText(json.encodeToString(updated))
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            File(dir, "$id.json").delete()
        }
    }

    suspend fun newSession(): ChatSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            val s = ChatSession()
            File(dir, "${s.id}.json").writeText(json.encodeToString(s))
            s
        }
    }
}
