package com.pocketllm.companion

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** One durable thing the companion knows about the user. */
@Serializable
data class MemoryFact(
    val id: String,
    val text: String,
    val createdAt: Long,
    /** Pinned facts are always injected and survive trimming. */
    val pinned: Boolean = false,
)

/**
 * Long-term memory: a small, human-readable list of facts about the user.
 *
 * Deliberately not embeddings. This runs on phones that also hold a GGUF model,
 * and a hand-editable list the user can see and correct is worth more here than
 * semantic search they cannot inspect. Relevance is keyword overlap plus pins.
 */
class CompanionMemory(context: Context) {

    private val file = File(context.filesDir, "companion_memory.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Volatile
    private var cached: List<MemoryFact>? = null

    /** File mtime the cache was built from, so external edits are picked up. */
    @Volatile
    private var cachedStamp: Long = -1L

    /**
     * The overlay service and the app UI hold separate instances of this class
     * over the same file, so the cache is validated against the file rather
     * than trusted — otherwise a fact added in Settings would be invisible to
     * a companion that is already running.
     *
     * mtime alone is not enough: two edits inside the same second can share a
     * timestamp on some filesystems, so the length is folded in as well.
     */
    private fun stamp(): Long {
        if (!file.exists()) return 0L
        return file.lastModified() * 31L + file.length()
    }

    fun all(): List<MemoryFact> {
        val stamp = stamp()
        cached?.let { if (stamp == cachedStamp) return it }
        val loaded = runCatching {
            if (file.exists()) json.decodeFromString<List<MemoryFact>>(file.readText()) else emptyList()
        }.getOrDefault(emptyList())
        cached = loaded
        cachedStamp = stamp
        return loaded
    }

    private fun persist(facts: List<MemoryFact>) {
        runCatching { file.writeText(json.encodeToString(facts)) }
        cached = facts
        cachedStamp = stamp()
    }

    fun add(text: String, pinned: Boolean = false): MemoryFact? {
        val clean = normalize(text) ?: return null
        val existing = all()
        // Skip near-duplicates: extraction runs repeatedly over a long
        // conversation and would otherwise pile up restatements.
        if (existing.any { sameMeaning(it.text, clean) }) return null
        val fact = MemoryFact(
            id = UUID.randomUUID().toString(),
            text = clean,
            createdAt = System.currentTimeMillis(),
            pinned = pinned,
        )
        persist((existing + fact).takeLast(MAX_FACTS))
        return fact
    }

    fun update(id: String, text: String) {
        val clean = normalize(text) ?: return
        persist(all().map { if (it.id == id) it.copy(text = clean) else it })
    }

    fun remove(id: String) {
        persist(all().filterNot { it.id == id })
    }

    fun setPinned(id: String, pinned: Boolean) {
        persist(all().map { if (it.id == id) it.copy(pinned = pinned) else it })
    }

    fun clear() = persist(emptyList())

    /**
     * Facts to inject into the system prompt: everything pinned, then whatever
     * scores highest against the current conversation, capped to a small budget
     * so a loaded memory never crowds out the actual conversation.
     */
    fun relevant(recentText: String, budget: Int = PROMPT_BUDGET): List<MemoryFact> {
        val facts = all()
        if (facts.isEmpty()) return emptyList()
        val pinned = facts.filter { it.pinned }
        val rest = facts.filterNot { it.pinned }
        if (rest.isEmpty()) return pinned.take(budget)

        val words = recentText.lowercase()
            .split(NON_WORD)
            .filter { it.length >= MIN_WORD }
            .toSet()

        val scored = rest.map { fact ->
            val factWords = fact.text.lowercase().split(NON_WORD).filter { it.length >= MIN_WORD }
            val overlap = factWords.count { it in words }
            // Newer facts break ties: someone's current situation matters more
            // than a detail from weeks ago.
            overlap * 1000L + fact.createdAt / 1_000_000L
        }
        return (pinned + rest.zip(scored).sortedByDescending { it.second }.map { it.first })
            .distinctBy { it.id }
            .take(budget)
    }

    fun promptBlock(recentText: String): String {
        val facts = relevant(recentText)
        if (facts.isEmpty()) return ""
        return buildString {
            appendLine("Things you remember about them:")
            for (fact in facts) appendLine("- ${fact.text}")
            appendLine("Use these naturally when relevant. Never recite the list.")
        }
    }

    /**
     * Cheap pattern-based capture. Runs on every turn so obvious facts land
     * instantly, with no extra model call.
     */
    fun captureFromUserText(text: String): MemoryFact? {
        val trimmed = text.trim()
        if (trimmed.length < 6) return null

        // Explicit request: "remember that I ...", "note that ...".
        for (pattern in EXPLICIT_PATTERNS) {
            val match = pattern.find(trimmed) ?: continue
            val tail = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (tail.length in 3..160) return add(toThirdPerson(tail))
        }

        // "my name is X" reads better as its own fact than a converted clause.
        NAME_PATTERN.find(trimmed)?.let { match ->
            val name = match.groupValues.getOrNull(1)?.trim()?.trimEnd('.', ',', '!') ?: return@let
            if (name.length in 2..40 && name.split(' ').size <= 3) {
                return add("Their name is $name")
            }
        }

        return captureSelfDescription(trimmed)
    }

    /**
     * A statement the user makes about themselves — "I have a cat", "my
     * favourite food is sushi".
     *
     * The whole clause is kept, not just its tail: extracting only what follows
     * the verb turns "I live in Berlin" into the useless fragment "Berlin".
     */
    private fun captureSelfDescription(text: String): MemoryFact? {
        // Anything before a question mark is a question, not a fact.
        val sentence = text.substringBefore('?').trim()
        if (sentence.length < 12 || sentence.length > 200) return null
        if (!SELF_START.containsMatchIn(sentence)) return null
        val lower = sentence.lowercase()
        // Requests and passing states are not durable facts.
        if (NON_FACTS.any { lower.contains(it) }) return null
        return add(toThirdPerson(sentence.trimEnd('.', ',', ';', '!')))
    }

    /**
     * The prompt tells the model the list describes the user, so "I have a cat"
     * must be stored as "They have a cat". Without this the model is handed a
     * first-person list and can echo it back as its own.
     */
    private fun toThirdPerson(text: String): String {
        var out = text.trim()
        for ((pattern, replacement) in PRONOUN_STARTS) {
            if (pattern.containsMatchIn(out)) {
                out = pattern.replaceFirst(out, replacement)
                break
            }
        }
        out = out
            .replace(Regex("(?i)\\bmy\\b"), "their")
            .replace(Regex("(?i)\\bme\\b"), "them")
        // A mid-sentence possessive swap can leave the fact starting lowercase.
        return out.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /** Merge facts produced by the model extractor. Returns how many were new. */
    fun mergeExtracted(lines: List<String>): Int {
        var added = 0
        for (line in lines) {
            val clean = line.trim().removePrefix("-").removePrefix("*").trim()
            if (clean.isEmpty() || clean.equals("NONE", ignoreCase = true)) continue
            if (clean.length > 200) continue
            if (add(clean) != null) added++
        }
        return added
    }

    private fun normalize(text: String): String? {
        val clean = text.trim()
            .removeSurrounding("\"")
            .replace(Regex("\\s+"), " ")
            .trimEnd('.', ',', ';')
        return clean.takeIf { it.length in 3..200 }
    }

    private fun sameMeaning(a: String, b: String): Boolean {
        val na = a.lowercase().trim()
        val nb = b.lowercase().trim()
        if (na == nb) return true
        // Word-set equality catches "their name is Alice" vs "their name is alice."
        val wa = na.split(NON_WORD).filter { it.length > 2 }.toSet()
        val wb = nb.split(NON_WORD).filter { it.length > 2 }.toSet()
        return wa.isNotEmpty() && wa == wb
    }

    companion object {
        private const val MAX_FACTS = 200
        private const val PROMPT_BUDGET = 12
        private const val MIN_WORD = 4
        private val NON_WORD = Regex("[^a-z0-9]+")

        /**
         * Only genuine "please remember this" phrasings. Statements the user
         * makes about themselves are handled by [captureSelfDescription], which
         * keeps the whole clause instead of a fragment.
         */
        private val EXPLICIT_PATTERNS = listOf(
            Regex("(?i)\\bremember (?:that )?(.{3,160})"),
            Regex("(?i)\\bnote that (.{3,160})"),
            Regex("(?i)\\bfor future reference,? (.{3,160})"),
        )

        private val NAME_PATTERN = Regex("(?i)\\bmy name is ([^.!?\\n]{2,40})")

        /** A sentence about the user starts with one of these. */
        private val SELF_START =
            Regex("(?i)^(i|i'm|i am|i've|i have|i'd|i'll|my|mine|we|our)\\b")

        /** Not facts: instructions to the companion, or passing states. */
        private val NON_FACTS = listOf(
            "can you", "could you", "would you", "please ", "summarize", "explain this",
            "show me", "not sure", "i think", "maybe", "right now", "today i", "i feel",
            "sorry", "i'm trying to",
        )

        /**
         * Leading pronoun conversions, longest first so "I am" wins over "I".
         */
        private val PRONOUN_STARTS = listOf(
            Regex("(?i)^i'm\\b") to "They're",
            Regex("(?i)^i am\\b") to "They are",
            Regex("(?i)^i've\\b") to "They've",
            Regex("(?i)^i have\\b") to "They have",
            Regex("(?i)^i'll\\b") to "They'll",
            Regex("(?i)^i'd\\b") to "They'd",
            Regex("(?i)^i\\b") to "They",
            Regex("(?i)^we\\b") to "They",
            Regex("(?i)^our\\b") to "Their",
        )
    }
}
