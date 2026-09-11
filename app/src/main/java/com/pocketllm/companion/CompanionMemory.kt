package com.pocketllm.companion

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * What kind of thing a fact is.
 *
 * The category is not decoration: it decides how the fact is ranked when the
 * prompt budget is tight, whether it is allowed to go stale, and how the list
 * is grouped in Settings.
 */
enum class MemoryCategory(val id: String, val label: String) {
    Identity("identity", "Identity"),
    People("people", "People & pets"),
    Work("work", "Work & study"),
    Preference("preference", "Preferences"),
    Health("health", "Health"),
    Place("place", "Places"),
    Routine("routine", "Routines"),
    Other("other", "Other");

    companion object {
        fun byId(id: String?): MemoryCategory =
            entries.firstOrNull { it.id == id } ?: Other

        /**
         * Categories whose content tends to stay true. A name does not expire,
         * so these are never treated as stale and never decay.
         */
        val DURABLE = setOf(Identity, People, Work)
    }
}

/** One durable thing the companion knows about the user. */
@Serializable
data class MemoryFact(
    val id: String,
    val text: String,
    val createdAt: Long,
    /** Pinned facts are always injected and survive trimming. */
    val pinned: Boolean = false,
    /** See [MemoryCategory]; stored as its id so the file stays readable. */
    val category: String = MemoryCategory.Other.id,
    /**
     * 0..1. Raised when the same fact is heard again, lowered by nothing —
     * decay is expressed through [lastRecalledAt], not by eroding this, so a
     * fact never silently loses meaning.
     */
    val confidence: Float = 0.7f,
    /** When this fact was last confirmed, by the user or by being repeated. */
    val updatedAt: Long = 0L,
    /** Last time it was injected into a prompt. */
    val lastRecalledAt: Long = 0L,
    /** How often it has been injected. */
    val timesRecalled: Int = 0,
    /** How many times it has been heard again after first being stored. */
    val reinforcements: Int = 0,
) {
    val categoryValue: MemoryCategory get() = MemoryCategory.byId(category)

    /**
     * Whether this might no longer be true.
     *
     * Only ever for categories that can change, and only after a long quiet
     * spell — a preference nobody has mentioned in six months is worth a
     * second look, a person's name never is.
     */
    fun isStale(now: Long = System.currentTimeMillis()): Boolean {
        if (categoryValue in MemoryCategory.DURABLE) return false
        if (pinned) return false
        val last = maxOf(updatedAt, lastRecalledAt, createdAt)
        return now - last > STALE_AFTER_MS
    }

    companion object {
        /** Roughly six months: long enough that normal silence is not staleness. */
        const val STALE_AFTER_MS = 180L * 24 * 60 * 60 * 1000
    }
}

/**
 * Long-term memory: a small, human-readable list of facts about the user.
 *
 * Deliberately not embeddings. This runs on phones that also hold a GGUF model,
 * and a hand-editable list the user can see and correct is worth more here than
 * semantic search they cannot inspect.
 *
 * Recall is keyword overlap, but ranked with three corrections that a plain
 * sort gets wrong: overlap must dominate recency, confidence must break ties,
 * and facts the conversation is actually about must outrank facts that merely
 * happen to be new.
 */
class CompanionMemory(context: Context) {

    private val file = File(context.filesDir, "companion_memory.json")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

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
        val previous = cached
        val loaded = runCatching {
            if (file.exists()) json.decodeFromString<List<MemoryFact>>(file.readText()) else emptyList()
        }.getOrNull()
        // A parse failure on a file that has content is not evidence that memory
        // is empty — it is evidence that something went wrong. Keep the last
        // known-good list rather than returning nothing, because a caller that
        // sees an empty list can persist it and destroy the real data.
        val resolved = when {
            loaded != null -> loaded
            previous != null -> previous
            else -> emptyList()
        }
        // Older files predate updatedAt; treat creation as the last confirmation
        // so they do not all read as stale the first time this runs.
        val migrated = resolved.map { if (it.updatedAt == 0L) it.copy(updatedAt = it.createdAt) else it }
        cached = migrated
        cachedStamp = stamp()
        return migrated
    }

    /**
     * Writes happen off the caller's thread.
     *
     * [persist] is reached from prompt building, which runs on the service's
     * main dispatcher; a disk write there is a jank risk for no benefit, since
     * the in-memory cache is already updated before the write is queued. A
     * single thread keeps the writes ordered.
     */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "companion-memory-write").apply { isDaemon = true }
    }

    private fun persist(facts: List<MemoryFact>) {
        // Cache first, so any reader immediately afterwards sees the new state
        // regardless of when the file write lands.
        cached = facts
        cachedStamp = stamp()
        val payload = runCatching { json.encodeToString(facts) }.getOrNull() ?: return
        runCatching { writer.execute { runCatching { writeAtomically(payload) } } }
    }

    /**
     * Write via a temporary file and rename.
     *
     * The rename is atomic, which matters because writes are asynchronous: a
     * plain write could be read half-finished by another thread, fail to parse,
     * and be replaced by an empty list on the next save — losing everything.
     */
    private fun writeAtomically(payload: String) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(payload)
        if (!temp.renameTo(file)) {
            // Rename can fail if the target is locked; fall back to a direct
            // write rather than silently dropping the update.
            file.writeText(payload)
            temp.delete()
        }
    }

    fun add(
        text: String,
        pinned: Boolean = false,
        confidence: Float = CONFIDENCE_PATTERN,
    ): MemoryFact? {
        val clean = normalize(text) ?: return null
        val existing = all()
        // Heard it before: strengthen what is already there instead of storing
        // a second copy. Returns null because nothing new was learned.
        existing.firstOrNull { sameMeaning(it.text, clean) }?.let { known ->
            reinforce(known.id)
            return null
        }
        // A new value for a single-valued relation retires the old one, so she
        // does not still think you live in the city you moved away from.
        val superseded = existing.filter { supersedes(it.text, clean) }.map { it.id }.toSet()
        val kept = if (superseded.isEmpty()) existing else existing.filterNot { it.id in superseded }
        val fact = MemoryFact(
            id = UUID.randomUUID().toString(),
            text = clean,
            createdAt = System.currentTimeMillis(),
            pinned = pinned,
            category = classify(clean).id,
            confidence = confidence.coerceIn(0f, 1f),
            updatedAt = System.currentTimeMillis(),
        )
        persist((kept + fact).takeLast(MAX_FACTS))
        return fact
    }

    /**
     * Whether [newText] makes [oldText] untrue.
     *
     * Only single-valued relations qualify — a person has one name, one home,
     * one job. Pets, people and preferences are deliberately excluded: hearing
     * about a second cat must not delete the first one.
     *
     * A restatement is never a replacement, so this defers to [sameMeaning]
     * first; otherwise "Berlin" would be retired by "Berlin, Germany".
     */
    private fun supersedes(oldText: String, newText: String): Boolean {
        if (sameMeaning(oldText, newText)) return false
        val old = slotOf(oldText) ?: return false
        val new = slotOf(newText) ?: return false
        if (old.first != new.first) return false
        return old.second != new.second
    }

    /** The relation and its value, for a single-valued fact; null otherwise. */
    private fun slotOf(text: String): Pair<String, String>? {
        val lower = text.lowercase().trim().trimEnd('.')
        for ((slot, pattern) in SLOT_PATTERNS) {
            val match = pattern.find(lower) ?: continue
            return slot to match.groupValues[1].trim()
        }
        return null
    }

    /** Hearing the same thing again makes it more trustworthy, not more present. */
    fun reinforce(id: String, now: Long = System.currentTimeMillis()) {
        persist(
            all().map {
                if (it.id != id) it
                else it.copy(
                    confidence = (it.confidence + CONFIDENCE_STEP).coerceAtMost(1f),
                    reinforcements = it.reinforcements + 1,
                    updatedAt = now,
                )
            }
        )
    }

    fun update(id: String, text: String) {
        val clean = normalize(text) ?: return
        persist(
            all().map {
                // An edit is also a confirmation, and may reclassify the fact.
                if (it.id == id) it.copy(
                    text = clean,
                    category = if (it.category == MemoryCategory.Other.id) classify(clean).id else it.category,
                    confidence = 1f,
                    updatedAt = System.currentTimeMillis(),
                ) else it
            }
        )
    }

    fun setCategory(id: String, category: MemoryCategory) {
        persist(all().map { if (it.id == id) it.copy(category = category.id) else it })
    }

    fun remove(id: String) {
        persist(all().filterNot { it.id == id })
    }

    fun setPinned(id: String, pinned: Boolean) {
        persist(all().map { if (it.id == id) it.copy(pinned = pinned) else it })
    }

    fun clear() = persist(emptyList())

    /** Facts that might no longer be true, oldest first. */
    fun stale(now: Long = System.currentTimeMillis()): List<MemoryFact> =
        all().filter { it.isStale(now) }.sortedBy { maxOf(it.updatedAt, it.lastRecalledAt, it.createdAt) }

    /** Drop everything flagged stale. Explicit user action, never automatic. */
    fun forgetStale(now: Long = System.currentTimeMillis()): Int {
        val doomed = stale(now).map { it.id }.toSet()
        if (doomed.isEmpty()) return 0
        persist(all().filterNot { it.id in doomed })
        return doomed.size
    }

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

        val words = contentWords(recentText)
        val now = System.currentTimeMillis()

        val scored = rest.map { fact -> fact to score(fact, words, now) }
        return (pinned + scored.sortedByDescending { it.second }.map { it.first })
            .distinctBy { it.id }
            .take(budget)
    }

    /**
     * Ranking for one fact.
     *
     * The three terms are deliberately ordered by magnitude so their precedence
     * is unambiguous:
     *  - overlap, the thing that actually matters, is multiplied hugely;
     *  - confidence separates facts that are equally relevant;
     *  - recency is a bounded tie-break, and *bounded* is the point. The
     *    original code added `createdAt / 1_000_000`, which is about 1.8
     *    million today and swamped the overlap term entirely — so "relevance"
     *    was really "the twelve most recent facts". Capping it keeps the
     *    tie-break intent without letting it decide the ranking.
     */
    private fun score(fact: MemoryFact, queryWords: Set<String>, now: Long): Long {
        val factWords = contentWords(fact.text)
        val overlap = factWords.count { it in queryWords }
        val confidence = (fact.confidence * CONFIDENCE_WEIGHT).toLong()
        // Days old, inverted, and clamped: newer wins ties, but the whole term
        // stays smaller than a single unit of overlap.
        val daysOld = ((now - fact.createdAt) / DAY_MS).coerceAtLeast(0)
        val recency = (RECENCY_RANGE - daysOld).coerceIn(0, RECENCY_RANGE)
        return overlap * OVERLAP_WEIGHT + confidence + recency
    }

    /**
     * Note that these facts were just used.
     *
     * Guarded so a long conversation does not rewrite the file every turn: the
     * timestamp only moves once per [RECALL_WRITE_INTERVAL_MS] per fact, which
     * is plenty of resolution for a six-month staleness window.
     */
    fun noteUsed(ids: Collection<String>, now: Long = System.currentTimeMillis()) {
        if (ids.isEmpty()) return
        val wanted = ids.toSet()
        val facts = all()
        var changed = false
        val updated = facts.map { fact ->
            if (fact.id !in wanted) fact
            else if (now - fact.lastRecalledAt < RECALL_WRITE_INTERVAL_MS) fact
            else {
                changed = true
                fact.copy(lastRecalledAt = now, timesRecalled = fact.timesRecalled + 1)
            }
        }
        if (changed) persist(updated)
    }

    fun promptBlock(recentText: String): String {
        val facts = relevant(recentText)
        if (facts.isEmpty()) return ""
        // Marked as used so staleness reflects what she actually draws on.
        noteUsed(facts.map { it.id })
        val now = System.currentTimeMillis()
        return buildString {
            appendLine("Things you remember about them:")
            for (fact in facts) {
                append("- ")
                append(fact.text)
                // A hedged fact is stated as a possibility rather than a fact,
                // so a half-remembered detail cannot become a false certainty.
                if (fact.isStale(now)) append(" (from a while ago — mention only if relevant)")
                appendLine()
            }
            appendLine("Use these naturally when relevant. Never recite the list.")
        }
    }

    /** Free-text filter over the stored facts, for the Settings list. */
    fun search(query: String): List<MemoryFact> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all()
        return all().filter {
            it.text.lowercase().contains(q) || it.categoryValue.label.lowercase().contains(q)
        }
    }

    /** Counts per category, for the Settings summary. */
    fun countsByCategory(): Map<MemoryCategory, Int> =
        all().groupingBy { it.categoryValue }.eachCount()

    /**
     * The whole list as JSON, so it can be moved between devices or kept as a
     * backup. Deliberately the same shape as the on-disk file.
     */
    fun exportJson(): String = json.encodeToString(all())

    /**
     * Merge a previously exported list. Returns how many facts were new.
     *
     * Goes through [add] rather than overwriting, so importing the same file
     * twice does not duplicate anything and an import cannot wipe memory the
     * user has built up since the export was taken.
     */
    fun importJson(raw: String): Int {
        val incoming = runCatching { json.decodeFromString<List<MemoryFact>>(raw) }.getOrDefault(emptyList())
        var added = 0
        for (fact in incoming) {
            val created = add(fact.text, pinned = fact.pinned, confidence = fact.confidence)
            if (created != null) added++
        }
        return added
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
            // An explicit ask is a deliberate statement, so it is trusted more.
            if (tail.length in 3..160) return add(toThirdPerson(tail), confidence = CONFIDENCE_EXPLICIT)
        }

        // "my name is X" reads better as its own fact than a converted clause.
        NAME_PATTERN.find(trimmed)?.let { match ->
            val name = match.groupValues.getOrNull(1)?.trim()?.trimEnd('.', ',', '!') ?: return@let
            if (name.length in 2..40 && name.split(' ').size <= 3) {
                return add("Their name is $name", confidence = CONFIDENCE_EXPLICIT)
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
        return add(toThirdPerson(sentence.trimEnd('.', ',', ';', '!')), confidence = CONFIDENCE_PATTERN)
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
            if (add(clean, confidence = CONFIDENCE_MODEL) != null) added++
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

    /**
     * The words that actually carry meaning, for matching, reduced to stems.
     *
     * Short words are *not* excluded on length alone: "cat", "dog", "job" and
     * people's names like "Ada" are three letters and are exactly the words
     * someone asks about. A stopword list is used instead, so those survive
     * while "the", "and", "for" do not.
     *
     * Words are stemmed so "cats" matches "cat" and "working" matches "work" —
     * without it, recall misses the most natural way a question is phrased.
     */
    private fun contentWords(text: String): Set<String> =
        text.lowercase()
            .split(NON_WORD)
            .filter { it.length >= MIN_WORD && it !in STOPWORDS }
            .map { stem(it) }
            .toSet()

    /**
     * A deliberately small suffix stripper.
     *
     * Not a real stemmer — it handles plurals and the two most common verb
     * endings, and stops. Anything more aggressive starts merging words that
     * mean different things, which is worse for recall than missing a match.
     */
    private fun stem(word: String): String {
        if (word.length <= 3) return word
        if (word.endsWith("ies") && word.length > 4) return word.dropLast(3) + "y"
        if (word.endsWith("ing") && word.length > 5) return unDouble(word.dropLast(3))
        if (word.endsWith("ed") && word.length > 4) return unDouble(word.dropLast(2))
        // "-es" drops both letters only after a sibilant ("classes", "boxes");
        // elsewhere it is an ordinary plural and only the s goes ("houses").
        if (word.endsWith("es") && word.length > 4 &&
            SIBILANT_ENDS.any { word.dropLast(2).endsWith(it) }
        ) {
            return word.dropLast(2)
        }
        if (word.endsWith("s") && !word.endsWith("ss") && word.length > 3) return word.dropLast(1)
        return word
    }

    /**
     * running -> run, stopped -> stop.
     *
     * Excludes l/s/f/z because English does not double those before these
     * endings in a way that should collapse ("fall" must stay "fall").
     */
    private fun unDouble(word: String): String {
        if (word.length > 3 && word.last() == word[word.length - 2] &&
            word.last() !in "lsfz"
        ) {
            return word.dropLast(1)
        }
        return word
    }

    /**
     * Whether two facts say the same thing.
     *
     * Exact or same word-set, plus one carefully bounded case: a fact that only
     * gains a qualifier ("Berlin" -> "Berlin, Germany"). The bound is what makes
     * it safe — an unguarded subset test would fold "They live in Berlin and
     * work as a nurse" into "They live in Berlin" and silently lose the job.
     */
    private fun sameMeaning(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        val wa = contentWords(a)
        val wb = contentWords(b)
        if (wa.isEmpty() || wb.isEmpty()) return false
        if (wa == wb) return true

        val (small, big, supersetText) =
            if (wa.size <= wb.size) Triple(wa, wb, b) else Triple(wb, wa, a)
        if (!big.containsAll(small)) return false
        if (big.size - small.size > MAX_QUALIFIER_WORDS) return false
        // A conjunction adds a clause rather than qualifying an existing one.
        return NON_WORD.split(supersetText.lowercase()).none { it in CONJUNCTIONS }
    }

    companion object {
        private const val MAX_FACTS = 200
        private const val PROMPT_BUDGET = 12
        /**
         * Three, not four: names and everyday nouns ("cat", "car", "job") are
         * exactly the words a question is likely to turn on.
         */
        private const val MIN_WORD = 3
        /** Extra words a fact may gain and still count as the same fact. */
        private const val MAX_QUALIFIER_WORDS = 2

        /** Overlap is the signal, so it is weighted above everything else. */
        private const val OVERLAP_WEIGHT = 1_000_000L
        /** Max confidence contribution; below the overlap unit on purpose. */
        private const val CONFIDENCE_WEIGHT = 10_000f
        /**
         * Upper bound on the recency tie-break, in days-old being subtracted
         * from it. 9999 days is ~27 years, so it never saturates for real use,
         * and the term stays far below [OVERLAP_WEIGHT].
         */
        private const val RECENCY_RANGE = 9_999L
        private const val DAY_MS = 24L * 60 * 60 * 1000

        private const val CONFIDENCE_MODEL = 0.55f
        private const val CONFIDENCE_PATTERN = 0.7f
        private const val CONFIDENCE_EXPLICIT = 0.85f
        private const val CONFIDENCE_STEP = 0.15f

        /** At most one recall-stamp write per fact per six hours. */
        private const val RECALL_WRITE_INTERVAL_MS = 6L * 60 * 60 * 1000

        private val NON_WORD = Regex("[^a-z0-9]+")

        /**
         * Function words, not content words. Kept deliberately short — the only
         * job is to stop "the"/"and"/"for" from creating false matches.
         */
        private val STOPWORDS = setOf(
            "the", "and", "for", "you", "was", "are", "not", "but", "all", "can",
            "had", "her", "his", "one", "our", "out", "she", "who", "its", "has",
            "have", "they", "them", "their", "with", "that", "this", "from",
            "your", "were", "been", "will", "would", "about", "into", "than",
            "then", "when", "what", "how", "why", "get", "got", "did", "does",
            "very", "too", "just", "some", "any", "more", "most", "also",
        )

        /** Joining words: their presence means a fact gained a clause. */
        private val CONJUNCTIONS = setOf("and", "but", "or", "also", "plus")

        /** Suffixes after which "-es" is a syllable of its own, so both go. */
        private val SIBILANT_ENDS = listOf("ss", "x", "z", "zz", "ch", "sh")

        /**
         * Relations that can only hold one value at a time.
         *
         * The value is group 1, so two facts match a slot and differ when the
         * same relation names something else. Deliberately short: anything
         * multi-valued (pets, people, preferences) must never be superseded.
         */
        private val SLOT_PATTERNS: List<Pair<String, Regex>> = listOf(
            "name" to Regex("^their name is (.+)$"),
            "home" to Regex("^they (?:live|are based|are from|come from)\\s+(?:in|at)\\s+(.+)$"),
            "home" to Regex("^they moved to (.+)$"),
            "job" to Regex("^they work (?:as|at|for) (.+)$"),
            "study" to Regex("^they (?:are studying|study|studied)\\s+(?:for\\s+)?(.+)$"),
        )

        /**
         * Category patterns, tried in order and the *earliest* match wins, so
         * "They live in Berlin and work as a nurse" is a place (the sentence is
         * about where they live) rather than a job.
         */
        private val CATEGORY_PATTERNS = listOf(
            MemoryCategory.Identity to Regex(
                "\\b(name is|call me|i am \\d+|my age|birthday|born in|pronouns)\\b",
            ),
            MemoryCategory.People to Regex(
                "\\b(cat|dog|pet|sister|brother|mother|father|mum|mom|dad|son|daughter|" +
                    "wife|husband|partner|girlfriend|boyfriend|friend|colleague|roommate|" +
                    "baby|child|children)\\b",
            ),
            MemoryCategory.Work to Regex(
                "\\b(work|job|studying|study|degree|university|college|school|company|" +
                    "boss|manager|employed|career|project|team|client)\\b",
            ),
            MemoryCategory.Preference to Regex(
                "\\b(favourite|favorite|prefer|love|like|hate|dislike|enjoy|" +
                    "can't stand|fan of|allergic|drink|drinks|eat|eats|coffee|tea|" +
                    "vegetarian|vegan)\\b",
            ),
            MemoryCategory.Health to Regex(
                "\\b(health|diagnos|medication|medicine|therapy|doctor|condition|injur|" +
                    "asthma|diabet|anxiety|depress|sleep|diet|gym)\\b",
            ),
            MemoryCategory.Place to Regex(
                "\\b(live in|live at|lives in|from|based in|moved to|city|country|" +
                    "apartment|flat|home is|address)\\b",
            ),
            MemoryCategory.Routine to Regex(
                "\\b(every day|every morning|usually|routine|habit|each week|on mondays|" +
                    "schedule|wake up|bedtime)\\b",
            ),
        )

        /**
         * The earliest-matching category wins, so word order in the sentence
         * decides — which is what makes "live in Berlin and work as a nurse"
         * read as a place rather than a job.
         */
        fun classify(text: String): MemoryCategory {
            val lower = text.lowercase()
            var winner = MemoryCategory.Other
            var winnerAt = Int.MAX_VALUE
            for ((category, pattern) in CATEGORY_PATTERNS) {
                val at = pattern.find(lower)?.range?.first ?: continue
                if (at < winnerAt) {
                    winner = category
                    winnerAt = at
                }
            }
            return winner
        }

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
