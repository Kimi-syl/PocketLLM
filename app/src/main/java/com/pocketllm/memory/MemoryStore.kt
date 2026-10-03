package com.pocketllm.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Long-term memory: durable facts the model should keep across conversations,
 * stored in SQLite so they survive model swaps and are queryable.
 *
 * Shape is deliberately flat — (subject, fact) — because that is what small
 * models can reliably consume in a prompt ("阿媽 = Mother", "dog's name = Momo").
 * [promptBlock] renders the highest-value entries as a compact list for
 * injection into a system prompt; [search] backs lookups such as "who is 阿媽".
 *
 * Calls are synchronous and cheap (a few hundred rows); callers move them off
 * the main thread. The helper is a singleton per process via [get].
 */
class MemoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class Entry(
        val id: Long,
        val subject: String,
        val fact: String,
        val createdAt: Long,
        val hits: Int,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE memory (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                subject    TEXT NOT NULL,
                fact       TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                hits       INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_memory_subject ON memory(subject)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Facts are user data, but the schema is v1; a future migration must
        // preserve rows rather than dropping. Recreate only when it is safe.
        if (oldVersion < DB_VERSION) onCreate(db)
    }

    /** Insert a fact. Returns the new row id. */
    fun remember(subject: String, fact: String): Long {
        val s = subject.trim()
        val f = fact.trim()
        if (s.isEmpty() || f.isEmpty()) return -1L
        val cv = ContentValues().apply {
            put("subject", s)
            put("fact", f)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("memory", null, cv)
    }

    fun forget(id: Long): Boolean =
        writableDatabase.delete("memory", "id = ?", arrayOf(id.toString())) > 0

    fun clear() {
        writableDatabase.delete("memory", null, null)
    }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM memory", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** Newest first. */
    fun all(limit: Int = 200): List<Entry> =
        readableDatabase.rawQuery(
            "SELECT id, subject, fact, created_at, hits FROM memory ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c -> readAll(c) }

    /** Substring match on subject or fact, bumping the hit counter of matches. */
    fun search(query: String, limit: Int = 20): List<Entry> {
        val q = "%${query.trim()}%"
        val rows = readableDatabase.rawQuery(
            "SELECT id, subject, fact, created_at, hits FROM memory " +
                "WHERE subject LIKE ? OR fact LIKE ? ORDER BY hits DESC, created_at DESC LIMIT ?",
            arrayOf(q, q, limit.toString()),
        ).use { c -> readAll(c) }
        if (rows.isNotEmpty()) {
            val ids = rows.joinToString(",") { it.id.toString() }
            writableDatabase.execSQL("UPDATE memory SET hits = hits + 1 WHERE id IN ($ids)")
        }
        return rows
    }

    /**
     * Compact block for a system prompt, newest first. Empty when nothing is
     * stored, so callers can append unconditionally.
     */
    fun promptBlock(limit: Int = 25): String {
        val rows = all(limit)
        if (rows.isEmpty()) return ""
        return buildString {
            appendLine("Known facts about the user (long-term memory):")
            for (r in rows) appendLine("- ${r.subject}: ${r.fact}")
        }.trim()
    }

    private fun readAll(c: Cursor): List<Entry> {
        val out = ArrayList<Entry>(c.count)
        while (c.moveToNext()) {
            out += Entry(
                id = c.getLong(0),
                subject = c.getString(1),
                fact = c.getString(2),
                createdAt = c.getLong(3),
                hits = c.getInt(4),
            )
        }
        return out
    }

    companion object {
        private const val DB_NAME = "pocketllm_memory.db"
        private const val DB_VERSION = 1

        @Volatile
        private var instance: MemoryStore? = null

        fun get(context: Context): MemoryStore =
            instance ?: synchronized(this) {
                instance ?: MemoryStore(context).also { instance = it }
            }
    }
}
