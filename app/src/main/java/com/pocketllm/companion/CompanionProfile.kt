package com.pocketllm.companion

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * A saved companion — name, voice, traits, bubble face and colour.
 *
 * Deliberately separate from [AppSettings]: settings hold the companion that is
 * active right now, profiles are the library of ones you can switch to. Keeping
 * them apart means applying a profile can never leave half the settings on one
 * companion and half on another.
 */
@Serializable
data class CompanionProfile(
    val id: String = "",
    val name: String = "Momo",
    val style: String = CompanionPersonas.DEFAULT_ID,
    val warmth: Int = 70,
    val directness: Int = 35,
    val playfulness: Int = 45,
    val verbosity: Int = 30,
    val persona: String = "",
    val glyph: String = "\uD83D\uDC31",
    /** ARGB packed into a Long; 0 means "follow the app theme". */
    val bubbleColor: Long = 0L,
    val createdAt: Long = 0L,
)

/** The shareable wrapper, so an exported file carries its own version. */
@Serializable
data class CompanionProfileBundle(
    val version: Int = 1,
    val profiles: List<CompanionProfile> = emptyList(),
)

/**
 * Reads and writes the profile library, and moves it to and from shareable
 * JSON so companions can be backed up or handed to someone else.
 */
class CompanionProfileStore(context: Context) {

    private val file = File(context.filesDir, "companion_profiles.json")

    /**
     * [encodeDefaults] is on deliberately. kotlinx omits any field equal to its
     * default unless told otherwise, which would drop most of a profile from
     * the exported text — harmless for our own round-trip, but this JSON is
     * also a shareable/readable format, and it should carry every field.
     */
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()

    private var cached: List<CompanionProfile>? = null
    private var cachedStamp: String? = null

    fun all(): List<CompanionProfile> {
        val stamp = stamp()
        val current = cached
        if (current != null && stamp == cachedStamp) return current
        val loaded = if (file.exists()) {
            runCatching { json.decodeFromString<CompanionProfileBundle>(file.readText()).profiles }
                .getOrDefault(emptyList())
        } else emptyList()
        cached = loaded
        cachedStamp = stamp()
        return loaded
    }

    suspend fun save(profile: CompanionProfile): List<CompanionProfile> = mutex.withLock {
        val stamped = if (profile.id.isBlank()) {
            profile.copy(id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis())
        } else profile
        val next = all().filterNot { it.id == stamped.id } + stamped
        persist(next)
        next
    }

    suspend fun delete(id: String): List<CompanionProfile> = mutex.withLock {
        val next = all().filterNot { it.id == id }
        persist(next)
        next
    }

    /** Current library as pretty JSON, for sharing or backup. */
    fun export(profiles: List<CompanionProfile> = all()): String =
        json.encodeToString(CompanionProfileBundle(profiles = profiles))

    /**
     * Parse an exported bundle. Throws [IllegalArgumentException] with a
     * readable message rather than a serializer stack trace, since the input
     * comes from a paste box.
     */
    fun parse(text: String): List<CompanionProfile> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Nothing to import.")
        // SerializationException is itself an IllegalArgumentException; catching
        // broadly here is deliberate because the input is arbitrary user text.
        val bundle = try {
            json.decodeFromString<CompanionProfileBundle>(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("That doesn't look like an exported companion.")
        }
        if (bundle.profiles.isEmpty()) throw IllegalArgumentException("No companions found in that text.")
        // Re-id on import so a re-import never collides with an existing entry.
        return bundle.profiles.map { it.sanitised().copy(id = UUID.randomUUID().toString()) }
    }

    /** Returns how many were actually added, after duplicate removal. */
    suspend fun importAll(text: String): Int = mutex.withLock {
        val incoming = parse(text)
        val existing = all()
        // Skip anything already in the library, so importing the same bundle
        // twice doesn't quietly pile up duplicates.
        val known = existing.map { it.contentKey() }.toSet()
        val fresh = incoming.filterNot { it.contentKey() in known }
        if (fresh.isNotEmpty()) persist(existing + fresh)
        fresh.size
    }

    /** Identity of a profile's content, used to detect duplicates. */
    private fun CompanionProfile.contentKey(): String =
        "$name|$style|$persona|$glyph|$warmth|$directness|$playfulness|$verbosity|$bubbleColor"

    /**
     * Clamp anything that could come from an untrusted file. Traits are bounded
     * so a hand-edited bundle can't produce a nonsense prompt, and the glyph is
     * capped so it can't be a wall of text on the bubble.
     */
    private fun CompanionProfile.sanitised() = copy(
        name = name.trim().take(40).ifBlank { "Momo" },
        style = CompanionPersonas.byId(style).id,
        warmth = warmth.coerceIn(0, 100),
        directness = directness.coerceIn(0, 100),
        playfulness = playfulness.coerceIn(0, 100),
        verbosity = verbosity.coerceIn(0, 100),
        persona = persona.take(4000),
        glyph = glyph.trim().take(8).ifBlank { "\uD83D\uDC31" },
        createdAt = System.currentTimeMillis(),
    )

    private fun persist(profiles: List<CompanionProfile>) {
        file.writeText(json.encodeToString(CompanionProfileBundle(profiles = profiles)))
        cached = profiles
        cachedStamp = stamp()
    }

    /** mtime plus size, so two writes in the same second are still detected. */
    private fun stamp(): String =
        if (file.exists()) "${file.lastModified()}:${file.length()}" else "missing"
}
