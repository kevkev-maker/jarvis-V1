package com.example.myapp.jarvis

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * JARVIS's long-term memory: notes you ask him to remember, and reminders.
 * Stored only on this phone (a small private database). Nothing is uploaded,
 * except that notes are included in questions sent to the optional AI.
 */
class MemoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "jarvis_memory.db", null, 2) {

    data class Note(val id: Long, val text: String, val createdAt: Long)
    data class Reminder(val id: Long, val text: String, val at: Long)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "text TEXT NOT NULL, created_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE reminders (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "text TEXT NOT NULL, remind_at INTEGER NOT NULL, done INTEGER NOT NULL DEFAULT 0)"
        )
        createAliases(db)
    }

    private fun createAliases(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS aliases (word TEXT PRIMARY KEY, contact TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createAliases(db)
    }

    // ─── notes ───────────────────────────────────────────────────────────────

    @Synchronized
    fun addNote(text: String): Long {
        val cv = ContentValues().apply {
            put("text", text)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("notes", null, cv)
    }

    /** Newest first. */
    @Synchronized
    fun notes(limit: Int = 50): List<Note> {
        val out = mutableListOf<Note>()
        readableDatabase.rawQuery(
            "SELECT id, text, created_at FROM notes ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Note(c.getLong(0), c.getString(1), c.getLong(2)))
        }
        return out
    }

    /** Deletes notes containing [keyword]. Returns how many were deleted. */
    @Synchronized
    fun forget(keyword: String): Int =
        writableDatabase.delete("notes", "text LIKE ?", arrayOf("%$keyword%"))

    @Synchronized
    fun forgetAll(): Int = writableDatabase.delete("notes", null, null)

    // ─── reminders ───────────────────────────────────────────────────────────

    @Synchronized
    fun addReminder(text: String, at: Long): Long {
        val cv = ContentValues().apply {
            put("text", text)
            put("remind_at", at)
            put("done", 0)
        }
        return writableDatabase.insert("reminders", null, cv)
    }

    /** Reminders not yet delivered, soonest first. */
    @Synchronized
    fun pendingReminders(): List<Reminder> {
        val out = mutableListOf<Reminder>()
        readableDatabase.rawQuery(
            "SELECT id, text, remind_at FROM reminders WHERE done = 0 ORDER BY remind_at ASC", null
        ).use { c ->
            while (c.moveToNext()) out.add(Reminder(c.getLong(0), c.getString(1), c.getLong(2)))
        }
        return out
    }

    /** Returns the reminder only if it hasn't been delivered yet. */
    @Synchronized
    fun pendingReminder(id: Long): Reminder? {
        readableDatabase.rawQuery(
            "SELECT id, text, remind_at FROM reminders WHERE id = ? AND done = 0",
            arrayOf(id.toString())
        ).use { c ->
            if (c.moveToFirst()) return Reminder(c.getLong(0), c.getString(1), c.getLong(2))
        }
        return null
    }

    @Synchronized
    fun markDone(id: Long) {
        val cv = ContentValues().apply { put("done", 1) }
        writableDatabase.update("reminders", cv, "id = ?", arrayOf(id.toString()))
    }

    // ─── contact nicknames ("mom" → "birth giver") ───────────────────────────

    @Synchronized
    fun setAlias(word: String, contact: String) {
        val cv = ContentValues().apply {
            put("word", word.lowercase().trim())
            put("contact", contact.trim())
        }
        writableDatabase.insertWithOnConflict("aliases", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun alias(word: String): String? {
        readableDatabase.rawQuery(
            "SELECT contact FROM aliases WHERE word = ?", arrayOf(word.lowercase().trim())
        ).use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    @Synchronized
    fun aliases(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        readableDatabase.rawQuery("SELECT word, contact FROM aliases ORDER BY word", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
        }
        return out
    }

    @Synchronized
    fun clearAliases() {
        writableDatabase.delete("aliases", null, null)
    }

    companion object {
        @Volatile private var instance: MemoryStore? = null

        fun get(context: Context): MemoryStore =
            instance ?: synchronized(this) {
                instance ?: MemoryStore(context).also { instance = it }
            }
    }
}
