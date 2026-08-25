package com.pocketllm.keys

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.UUID

@Serializable
data class ApiKeyEntry(
    val id: String,
    val name: String,
    val key: String,
    val createdAt: Long,
    var lastUsedAt: Long = 0L,
    var enabled: Boolean = true,
)

class ApiKeyRepository(context: Context) {

    private val file = File(context.filesDir, "api_keys.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private fun load(): MutableList<ApiKeyEntry> =
        if (file.exists()) {
            runCatching { json.decodeFromString<MutableList<ApiKeyEntry>>(file.readText()) }.getOrDefault(mutableListOf())
        } else mutableListOf()

    private fun save(entries: List<ApiKeyEntry>) {
        file.writeText(json.encodeToString(entries))
    }

    suspend fun list(): List<ApiKeyEntry> = mutex.withLock { load().toList() }

    suspend fun create(name: String): ApiKeyEntry = mutex.withLock {
        val entries = load()
        val entry = ApiKeyEntry(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "unnamed" },
            key = "sk-" + randomHex(24),
            createdAt = System.currentTimeMillis(),
        )
        entries.add(entry)
        save(entries)
        entry
    }

    suspend fun validate(raw: String?): ApiKeyEntry? = mutex.withLock {
        if (raw.isNullOrBlank()) return@withLock null
        val entries = load()
        val hit = entries.firstOrNull { it.key == raw && it.enabled } ?: return@withLock null
        hit.lastUsedAt = System.currentTimeMillis()
        save(entries)
        hit
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Unit = mutex.withLock {
        val entries = load()
        entries.find { it.id == id }?.enabled = enabled
        save(entries)
    }

    suspend fun delete(id: String): Unit = mutex.withLock {
        save(load().filterNot { it.id == id })
    }

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
