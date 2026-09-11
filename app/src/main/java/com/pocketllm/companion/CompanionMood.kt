package com.pocketllm.companion

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One recorded mood check-in. Score is 1 (very low) to 5 (very good). */
@Serializable
data class MoodEntry(
    val at: Long = 0L,
    val score: Int = 3,
)

@Serializable
data class MoodBundle(val version: Int = 1, val entries: List<MoodEntry> = emptyList())

/** The five points on the mood scale, with the faces shown in the panel. */
data class MoodChoice(val score: Int, val face: String, val label: String)

object MoodScale {
    val choices = listOf(
        MoodChoice(1, "\uD83D\uDE1E", "rough"),
        MoodChoice(2, "\uD83D\uDE15", "low"),
        MoodChoice(3, "\uD83D\uDE10", "okay"),
        MoodChoice(4, "\uD83D\uDE42", "good"),
        MoodChoice(5, "\uD83D\uDE04", "great"),
    )

    fun label(score: Int): String =
        choices.firstOrNull { it.score == score }?.label ?: "okay"

    fun face(score: Int): String =
        choices.firstOrNull { it.score == score }?.face ?: "\uD83D\uDE10"
}

/**
 * A light record of how the user has been, from explicit check-ins only.
 *
 * Nothing is inferred from what they say in passing — reading mood out of
 * ordinary messages would be both unreliable and intrusive. She asks, they
 * tap, and that is the whole record.
 */
class MoodJournalStore(context: Context) {

    private val file = File(context.filesDir, "companion_mood.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private var cached: List<MoodEntry>? = null
    private var cachedStamp: String? = null

    fun all(): List<MoodEntry> {
        val stamp = stamp()
        val current = cached
        if (current != null && stamp == cachedStamp) return current
        val loaded = if (file.exists()) {
            runCatching { json.decodeFromString<MoodBundle>(file.readText()).entries }
                .getOrDefault(emptyList())
        } else emptyList()
        cached = loaded
        cachedStamp = stamp()
        return loaded
    }

    suspend fun add(score: Int, at: Long = System.currentTimeMillis()): List<MoodEntry> =
        mutex.withLock {
            val next = (all() + MoodEntry(at = at, score = score.coerceIn(1, 5)))
                .takeLast(MAX_ENTRIES)
            persist(next)
            next
        }

    suspend fun clear(): List<MoodEntry> = mutex.withLock {
        persist(emptyList())
        emptyList()
    }

    /** Average score over the last [days], or null when nothing is recorded. */
    fun averageOver(days: Int, now: Long = System.currentTimeMillis()): Double? {
        val cutoff = now - days * 24L * 60 * 60 * 1000
        val recent = all().filter { it.at >= cutoff }
        if (recent.isEmpty()) return null
        return recent.sumOf { it.score }.toDouble() / recent.size
    }

    /**
     * One average per calendar day for the last [days], oldest first, with null
     * for a day that has no check-in.
     *
     * Days are cut at local midnight rather than by dividing the epoch, so the
     * bars line up with the user's days. A DST shift can make a "day" an hour
     * long or short, which does not matter for a chart.
     */
    fun dailyAverages(days: Int, now: Long = System.currentTimeMillis()): List<Double?> {
        val starts = ArrayList<Long>(days)
        repeat(days) { back ->
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = now
            cal.add(java.util.Calendar.DAY_OF_YEAR, -back)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            starts.add(cal.timeInMillis)
        }
        starts.reverse()
        val entries = all()
        val dayMs = 24L * 60 * 60 * 1000
        return starts.map { start ->
            val inDay = entries.filter { it.at >= start && it.at < start + dayMs }
            if (inDay.isEmpty()) null else inDay.sumOf { it.score }.toDouble() / inDay.size
        }
    }

    /**
     * A gentle line for the system prompt, or empty when there is nothing
     * worth saying. Deliberately understated: she should be a little more
     * careful with someone having a hard week, not narrate their mood back at
     * them.
     */
    fun promptHint(now: Long = System.currentTimeMillis()): String {
        val avg = averageOver(7, now) ?: return ""
        val recent = all().filter { it.at >= now - 7 * 24L * 60 * 60 * 1000 }
        return when {
            avg <= 2.2 && recent.size >= 2 ->
                "They have been checking in low for the last few days. Be a little " +
                    "gentler and give them room. Do not mention this unless they bring it up."
            avg >= 4.2 && recent.size >= 2 ->
                "They have been in good spirits lately. You can be a bit lighter with them."
            else -> ""
        }
    }

    /** Short summary for Settings, e.g. "mostly okay · 6 check-ins this week". */
    fun summary(now: Long = System.currentTimeMillis()): String {
        val avg = averageOver(7, now) ?: return "No check-ins yet."
        val count = all().count { it.at >= now - 7 * 24L * 60 * 60 * 1000 }
        val word = when {
            avg <= 1.8 -> "rough"
            avg <= 2.6 -> "low"
            avg <= 3.4 -> "okay"
            avg <= 4.2 -> "good"
            else -> "great"
        }
        return "Mostly $word · $count check-in${if (count == 1) "" else "s"} this week"
    }

    private fun persist(entries: List<MoodEntry>) {
        file.writeText(json.encodeToString(MoodBundle(entries = entries)))
        cached = entries
        cachedStamp = stamp()
    }

    private fun stamp(): String =
        if (file.exists()) "${file.lastModified()}:${file.length()}" else "missing"

    private companion object {
        const val MAX_ENTRIES = 500
    }
}
