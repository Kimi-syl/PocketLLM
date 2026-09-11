package com.pocketllm.companion

import java.util.Calendar

/**
 * The lines she uses when she reaches out first.
 *
 * Hand-written rather than generated: a check-in has to appear instantly and
 * reliably, and generating one would need a model loaded at an arbitrary
 * moment in the background. The personality still shows through the chat that
 * follows, which is where it matters.
 */
object CompanionNudges {

    private val MORNING = listOf(
        "Morning. Hope today starts gently for you.",
        "Just checking in — how did you sleep?",
        "New day. Anything on your mind?",
    )

    private val DAY = listOf(
        "Hey. Have you had a break recently?",
        "Thinking of you. How's it going?",
        "You've been quiet — no pressure, just saying hi.",
        "Small reminder to drink some water.",
    )

    private val EVENING = listOf(
        "Evening. How did today treat you?",
        "Winding down? I'm here if you want to talk it through.",
        "Hope today had at least one good moment in it.",
    )

    private val NIGHT = listOf(
        "Still up? Make sure you get some rest.",
        "It's late — I'm here, but you should sleep soon.",
    )

    /** Rotates so the same line never appears twice in a row. */
    private var lastLine: String? = null

    fun next(hour: Int): String {
        val pool = when {
            hour in 5..11 -> MORNING
            hour in 12..17 -> DAY
            hour in 18..21 -> EVENING
            else -> NIGHT
        }
        val choices = pool.filter { it != lastLine }.ifEmpty { pool }
        val pick = choices.random()
        lastLine = pick
        return pick
    }

    /** True when the current hour falls inside the quiet window. */
    fun isQuietNow(startHour: Int, endHour: Int): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val start = startHour.coerceIn(0, 23)
        val end = endHour.coerceIn(0, 23)
        if (start == end) return false
        // Handles the usual overnight case (22 -> 8) as well as same-day windows.
        return if (start < end) hour in start until end
        else hour >= start || hour < end
    }
}
