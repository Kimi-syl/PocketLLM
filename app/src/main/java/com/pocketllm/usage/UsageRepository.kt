package com.pocketllm.usage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class UsageRecord(
    val ts: Long,
    val source: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

data class UsageStats(
    val requests: Int,
    val promptTokens: Long,
    val completionTokens: Long,
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

class UsageRepository(context: Context) {

    private val file = File(context.filesDir, "usage.jsonl")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    suspend fun add(record: UsageRecord) = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.appendText(json.encodeToString(record) + "\n")
        }
    }

    suspend fun list(): List<UsageRecord> = mutex.withLock { readAll() }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) { file.delete() }
    }

    private fun readAll(): List<UsageRecord> =
        if (file.exists()) {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { json.decodeFromString<UsageRecord>(line) }.getOrNull() }
        } else emptyList()

    companion object {
        fun aggregate(records: List<UsageRecord>, fromTs: Long): UsageStats {
            val filtered = records.filter { it.ts >= fromTs }
            return UsageStats(
                requests = filtered.size,
                promptTokens = filtered.sumOf { it.promptTokens.toLong() },
                completionTokens = filtered.sumOf { it.completionTokens.toLong() },
            )
        }

        fun byModel(records: List<UsageRecord>): Map<String, UsageStats> =
            records.groupBy { it.model }.mapValues { (_, v) ->
                UsageStats(v.size, v.sumOf { it.promptTokens.toLong() }, v.sumOf { it.completionTokens.toLong() })
            }.toList().sortedByDescending { it.second.totalTokens }.toMap()

        fun bySource(records: List<UsageRecord>): Map<String, UsageStats> =
            records.groupBy { it.source }.mapValues { (_, v) ->
                UsageStats(v.size, v.sumOf { it.promptTokens.toLong() }, v.sumOf { it.completionTokens.toLong() })
            }.toList().sortedByDescending { it.second.totalTokens }.toMap()

        fun dailyTotals(records: List<UsageRecord>, days: Int): List<Pair<Long, Long>> {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val todayStart = cal.timeInMillis
            val dayMs = 24L * 60 * 60 * 1000
            return (days - 1 downTo 0).map { offset ->
                val start = todayStart - offset * dayMs
                val end = start + dayMs
                val total = records.filter { it.ts in start until end }.sumOf { it.totalTokens.toLong() }
                start to total
            }
        }
    }
}
