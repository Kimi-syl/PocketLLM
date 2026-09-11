package com.pocketllm.companion

import java.util.Calendar
import java.util.Locale

/**
 * Recognises the small, exactly-answerable requests a companion should never
 * ask a language model about.
 *
 * Language models are unreliable at arithmetic and at the current date — they
 * will produce a confident wrong number. These are deterministic instead, and
 * they need no model at all, so they also work before one is loaded.
 */
object CompanionQuickTools {

    /**
     * Only the single-argument functions the calculator actually implements.
     * Notably no `pow`, and no multi-argument functions at all, so "2 to the
     * power of 8" is normalised to `2^8` rather than `pow(2,8)`.
     */
    private val FUNCS = "(?:sqrt|log10|log|exp|sin|cos|tan|abs)"
    private val PLAIN = Regex("^[0-9+\\-*/^().]+$")
    private val FUNC_CALL = Regex("^$FUNCS\\([0-9+\\-*/^().]+\\)$")
    private val FUNC_EXPR = Regex("^(?:[0-9+\\-*/^().]*(?:$FUNCS\\([0-9+\\-*/^().]+\\)))+[0-9+\\-*/^().]*$")

    /** Leading filler and question words that carry no maths. */
    private val LEAD = Regex(
        "(?i)\\bwhat(?:'s| is)\\b|\\bhow much is\\b|\\bcalculate\\b|\\bcompute\\b|" +
            "\\bwork out\\b|\\bevaluate\\b|\\bsolve\\b|\\bplease\\b",
    )

    private val WORD_OPS = listOf(
        Regex("\\bto the power of\\b", RegexOption.IGNORE_CASE) to "^",
        Regex("\\braised to\\b", RegexOption.IGNORE_CASE) to "^",
        Regex("\\bplus\\b", RegexOption.IGNORE_CASE) to "+",
        Regex("\\bminus\\b", RegexOption.IGNORE_CASE) to "-",
        Regex("\\btimes\\b", RegexOption.IGNORE_CASE) to "*",
        Regex("\\bmultiplied by\\b", RegexOption.IGNORE_CASE) to "*",
        Regex("\\bdivided by\\b", RegexOption.IGNORE_CASE) to "/",
        Regex("\\bover\\b", RegexOption.IGNORE_CASE) to "/",
        Regex("\\bsquared\\b", RegexOption.IGNORE_CASE) to "^2",
        Regex("\\bcubed\\b", RegexOption.IGNORE_CASE) to "^3",
    )

    private val PERCENT_OF = Regex(
        "(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s*of\\s*(\\d+(?:\\.\\d+)?)",
        RegexOption.IGNORE_CASE,
    )

    /** Thousands separators only, so `pow`-style argument commas would survive. */
    private val THOUSANDS = Regex("(?<=\\d),(?=\\d{3}(?:\\D|$))")

    /**
     * A calculator-ready expression, or null when this isn't a maths request.
     *
     * Deliberately strict: a bare number, prose, or anything containing words
     * other than the known ones is rejected, so ordinary conversation is never
     * mistaken for a sum.
     */
    fun mathExpression(text: String): String? {
        val expression = normalize(text)
        if (expression.isEmpty() || expression.length > 120) return null
        if (!expression.any { it.isDigit() }) return null

        if (PLAIN.matches(expression)) {
            // A bare number is not a request to calculate anything.
            return if (expression.any { it in "+-*/^" }) expression else null
        }
        return if (FUNC_CALL.matches(expression) || FUNC_EXPR.matches(expression)) expression else null
    }

    private fun normalize(text: String): String {
        var s = LEAD.replace(text.trim(), " ")
        s = THOUSANDS.replace(s, "")
        s = PERCENT_OF.replace(s) { match ->
            // "15% of 240" is a proportion, not a remainder op.
            "(${match.groupValues[1]}/100)*${match.groupValues[2]}"
        }
        for ((pattern, replacement) in WORD_OPS) s = pattern.replace(s, replacement)
        s = s.trim().trimEnd('?', '=', ' ')
        return s.replace(Regex("\\s+"), "")
    }

    // --- Date and time ------------------------------------------------------

    private val TIME_REQUEST = Regex(
        "\\b(what(?:'s| is)? the time|what time is it|got the time|current time)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val DATE_REQUEST = Regex(
        "\\b(what(?:'s| is)? (?:the )?date|what day is it|what(?:'s| is) today(?:'s date)?|today's date)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val DAY_REQUEST = Regex(
        "\\b(what day(?: of the week)?(?: is it| is today))\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A spoken-style date/time answer, or null when they didn't ask.
     *
     * Formatted here rather than through the datetime tool, which returns raw
     * ISO strings — correct, but not how a companion should talk.
     */
    fun dateTimeAnswer(text: String, now: Calendar = Calendar.getInstance()): String? {
        when {
            DAY_REQUEST.containsMatchIn(text) ->
                return "It's ${now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault())}."
            DATE_REQUEST.containsMatchIn(text) ->
                return "It's ${formatDate(now)}."
            TIME_REQUEST.containsMatchIn(text) ->
                return "It's ${formatTime(now)}."
        }
        return null
    }

    private fun formatTime(now: Calendar): String =
        String.format(Locale.US, "%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))

    private fun formatDate(now: Calendar): String {
        val day = now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault())
        val month = now.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault())
        return "$day ${now.get(Calendar.DAY_OF_MONTH)} $month ${now.get(Calendar.YEAR)}"
    }
}
