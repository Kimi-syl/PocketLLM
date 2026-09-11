package com.pocketllm.companion

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar
import java.util.UUID

/** A reminder she is holding for the user. */
@Serializable
data class CompanionReminder(
    val id: String = "",
    /** What to remind them about, already stripped of "remind me to". */
    val subject: String = "",
    /** Wall-clock millis when it should fire. */
    val triggerAt: Long = 0L,
    val createdAt: Long = 0L,
)

@Serializable
data class ReminderBundle(val version: Int = 1, val reminders: List<CompanionReminder> = emptyList())

/** Persistent store for reminders; survives the service being killed. */
class CompanionReminderStore(context: Context) {

    private val file = File(context.filesDir, "companion_reminders.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    fun all(): List<CompanionReminder> = read().sortedBy { it.triggerAt }

    suspend fun add(reminder: CompanionReminder): CompanionReminder = mutex.withLock {
        val stamped = if (reminder.id.isBlank()) {
            reminder.copy(id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis())
        } else reminder
        write(read().filterNot { it.id == stamped.id } + stamped)
        stamped
    }

    suspend fun remove(id: String) = mutex.withLock {
        write(read().filterNot { it.id == id })
    }

    /** Drop reminders that fired more than [keepForMs] ago, so the list self-tidies. */
    suspend fun prune(keepForMs: Long = 24 * 60 * 60 * 1000L) = mutex.withLock {
        val cutoff = System.currentTimeMillis() - keepForMs
        val kept = read().filter { it.triggerAt > cutoff }
        if (kept.size != read().size) write(kept)
    }

    private fun read(): List<CompanionReminder> =
        if (!file.exists()) emptyList()
        else runCatching { json.decodeFromString<ReminderBundle>(file.readText()).reminders }
            .getOrDefault(emptyList())

    private fun write(reminders: List<CompanionReminder>) {
        file.writeText(json.encodeToString(ReminderBundle(reminders = reminders)))
    }
}

/**
 * Schedules and cancels reminder alarms.
 *
 * Uses [AlarmManager.setWindow] rather than an exact alarm: exact alarms need a
 * special user-granted permission on Android 12+, and a reminder arriving
 * within the minute is perfectly good. It also needs no permission at all,
 * which matters for something that should just work.
 */
object ReminderScheduler {

    const val EXTRA_ID = "com.pocketllm.companion.REMINDER_ID"
    private const val WINDOW_MS = 60_000L

    fun schedule(context: Context, reminder: CompanionReminder) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = pendingIntent(context, reminder.id)
        runCatching {
            // Already-past triggers fire immediately rather than being dropped.
            val at = reminder.triggerAt.coerceAtLeast(System.currentTimeMillis() + 1_000L)
            manager.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending)
        }
    }

    fun cancel(context: Context, id: String) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { manager.cancel(pendingIntent(context, id)) }
    }

    /** Human-friendly "18:30" / "tomorrow 09:00" for confirmations. */
    fun describe(triggerAt: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = triggerAt }
        val hm = String.format(java.util.Locale.US, "%02d:%02d", then.get(Calendar.HOUR_OF_DAY), then.get(Calendar.MINUTE))
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) hm else "tomorrow at $hm"
    }

    private fun pendingIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Understands "remind me to X in 20 minutes" well enough to schedule it.
 *
 * Deliberately deterministic rather than model-driven: a reminder that lands at
 * the wrong time is worse than no reminder, so this only handles the phrasings
 * it can be certain about and asks for a time otherwise.
 */
object ReminderParser {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** Hour above which a bare "at 9" means morning; at or below means evening. */
    private const val BARE_HOUR_PM_CUTOFF = 7

    private val RELATIVE = listOf(
        Regex("\\bin\\s+(?:half\\s+an?\\s+hour|30\\s*(?:minutes?|mins?|m))\\b", RegexOption.IGNORE_CASE) to 30 * MINUTE,
        Regex("\\bin\\s+an?\\s+hour\\b", RegexOption.IGNORE_CASE) to HOUR,
        Regex("\\bin\\s+(\\d+(?:\\.\\d+)?)\\s*(?:hours?|hrs?|h)\\b", RegexOption.IGNORE_CASE) to null,
        Regex("\\bin\\s+(\\d+)\\s*(?:minutes?|mins?|m)\\b", RegexOption.IGNORE_CASE) to null,
        Regex("\\bin\\s+(\\d+)\\s*(?:days?|d)\\b", RegexOption.IGNORE_CASE) to null,
    )

    private val TOMORROW_AT = Regex(
        "\\btomorrow\\s+at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b",
        RegexOption.IGNORE_CASE,
    )
    private val TODAY_AT = Regex(
        "\\bat\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b",
        RegexOption.IGNORE_CASE,
    )

    private val LEAD = Regex(
        "(?i)^\\s*(?:please\\s+|hey\\s+|can you\\s+|could you\\s+)*" +
            "remind me\\s*(?:to|about|that)?\\s*",
    )

    /** Bare unit suffixes, hoisted so they aren't recompiled per call. */
    private val DAY_SUFFIX = Regex("\\d\\s*d\\b")
    private val HOUR_SUFFIX = Regex("\\d\\s*h\\b")

    /** Milliseconds from now that a phrase implies, or null if unparseable. */
    fun parseTime(text: String, now: Long = System.currentTimeMillis()): Long? {
        val lower = text.lowercase()
        for ((pattern, fixed) in RELATIVE) {
            val match = pattern.find(lower) ?: continue
            val matched = match.value
            // Kept as Long throughout: mixing Long and Double branches would
            // leave the `when` with no common numeric type.
            val delta: Long = if (fixed != null) {
                fixed
            } else {
                val count = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                when {
                    "day" in matched || DAY_SUFFIX.containsMatchIn(matched) -> (count * DAY).toLong()
                    "hour" in matched || "hr" in matched || HOUR_SUFFIX.containsMatchIn(matched) ->
                        (count * HOUR).toLong()
                    else -> (count * MINUTE).toLong()
                }
            }
            if (delta > 0) return now + delta
        }

        TOMORROW_AT.find(text)?.let { match ->
            val (h, m) = hourMinute(match)
            return calendarAt(now, h % 24, m, daysAhead = 1)
        }

        TODAY_AT.find(text)?.let { match ->
            val (h, m) = hourMinute(match)
            val today = calendarAt(now, h % 24, m, daysAhead = 0)
            // A time that has already passed today means tomorrow.
            return if (today <= now) calendarAt(now, h % 24, m, daysAhead = 1) else today
        }

        return null
    }

    /** The subject of the reminder: the message minus the time and the lead-in. */
    fun parseSubject(text: String): String {
        var s = text
        // Remove whichever time expression matched, and the instruction around it.
        for ((pattern, _) in RELATIVE) s = s.replace(pattern, " ")
        s = s.replace(TOMORROW_AT, " ").replace(TODAY_AT, " ")
        s = s.replace(LEAD, "")
        return s.trim().trim('.', ',', '!', '?', '-', ' ').take(160)
    }

    /** True when this looks like a request to be reminded of something. */
    fun looksLikeReminder(text: String): Boolean =
        Regex("\\bremind\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun hourMinute(match: MatchResult): Pair<Int, Int> {
        var hour = match.groupValues[1].toIntOrNull() ?: 0
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val meridiem = match.groupValues.getOrNull(3)?.lowercase().orEmpty()
        hour = when {
            meridiem == "pm" && hour < 12 -> hour + 12
            meridiem == "am" && hour == 12 -> 0
            meridiem.isEmpty() && hour <= BARE_HOUR_PM_CUTOFF -> hour + 12
            else -> hour
        }
        return hour to minute.coerceIn(0, 59)
    }

    private fun calendarAt(now: Long, hour: Int, minute: Int, daysAhead: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, daysAhead)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
