package com.phoneai.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Device-local, structured conversation store inspired by NanoMuse's session/message repository.
 * The existing SharedPreferences archive is imported once, then SQLite is the source of truth.
 * Message bodies stay on-device; tool payloads and secrets are deliberately not stored here.
 */
internal class OrionConversationRepository private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "orion_conversations.db", null, 1) {

    data class Session(
        val id: String,
        val timestamp: Long,
        val title: String,
        val messages: List<Pair<String, String>>
    )

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sessions (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            """CREATE TABLE messages (
                id TEXT PRIMARY KEY NOT NULL,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                sort_order INTEGER NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )"""
        )
        db.execSQL("CREATE INDEX messages_session_order ON messages(session_id, sort_order)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun clean(turns: List<Pair<String, String>>) =
        turns.filter { (kind, text) -> (kind == "me" || kind == "ai") && text.isNotBlank() }
            .takeLast(MAX_TURNS).map { it.first to it.second.take(6000) }

    @Synchronized
    fun migrateLegacy(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MIGRATED, false)) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val active = readLegacyMessages(prefs.getString(ACTIVE_KEY, null))
            if (active.isNotEmpty()) {
                val id = prefs.getString(ACTIVE_ID, null)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                insertSession(db, id, titleFor(active), System.currentTimeMillis(), true, active)
                prefs.edit().putString(ACTIVE_ID, id).commit()
            }
            val archives = try { JSONArray(prefs.getString(ARCHIVE_KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
            for (i in 0 until archives.length()) {
                val item = archives.optJSONObject(i) ?: continue
                val turns = readLegacyMessages(item.optJSONArray("messages")?.toString())
                if (turns.isEmpty()) continue
                val timestamp = item.optLong("timestamp", System.currentTimeMillis() + i)
                val title = item.optString("title", titleFor(turns)).take(64).ifBlank { titleFor(turns) }
                val id = item.optString("sessionId").takeIf { it.isNotBlank() }
                    ?: "legacy-$timestamp-${title.hashCode().toUInt().toString(16)}"
                if (!sessionExists(db, id)) insertSession(db, id, title, timestamp, false, turns)
            }
            db.setTransactionSuccessful()
            prefs.edit().putBoolean(MIGRATED, true).commit()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun loadActive(context: Context): List<Pair<String, String>> {
        migrateLegacy(context)
        val db = readableDatabase
        val id = db.rawQuery("SELECT id FROM sessions WHERE is_active=1 ORDER BY updated_at DESC LIMIT 1", null).use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return emptyList()
        return readMessages(db, id)
    }

    @Synchronized
    fun saveActive(context: Context, transcript: List<Pair<String, String>>) {
        migrateLegacy(context)
        val turns = clean(transcript)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var id = db.rawQuery("SELECT id FROM sessions WHERE is_active=1 ORDER BY updated_at DESC LIMIT 1", null).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(ACTIVE_ID, id).commit()
                insertSession(db, id, titleFor(turns), System.currentTimeMillis(), true, emptyList())
            }
            val now = System.currentTimeMillis()
            val title = titleFor(turns)
            db.update("sessions", ContentValues().apply {
                put("title", title)
                put("updated_at", now)
            }, "id=?", arrayOf(id))
            db.delete("messages", "session_id=?", arrayOf(id))
            turns.forEachIndexed { index, pair -> insertMessage(db, id, pair.first, pair.second, now + index, index) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun archiveAndClear(context: Context, transcript: List<Pair<String, String>>) {
        migrateLegacy(context)
        val turns = clean(transcript)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.rawQuery("SELECT id FROM sessions WHERE is_active=1 ORDER BY updated_at DESC LIMIT 1", null).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (turns.any { it.first == "me" }) {
                val sessionId = id ?: UUID.randomUUID().toString()
                if (id == null) insertSession(db, sessionId, titleFor(turns), System.currentTimeMillis(), false, emptyList())
                db.update("sessions", ContentValues().apply {
                    put("title", titleFor(turns))
                    put("updated_at", System.currentTimeMillis())
                    put("is_active", 0)
                }, "id=?", arrayOf(sessionId))
                db.delete("messages", "session_id=?", arrayOf(sessionId))
                val now = System.currentTimeMillis()
                turns.forEachIndexed { index, pair -> insertMessage(db, sessionId, pair.first, pair.second, now + index, index) }
            } else if (id != null) {
                db.delete("sessions", "id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ACTIVE_KEY).remove(ACTIVE_ID).commit()
    }

    @Synchronized
    fun archived(context: Context): List<Session> {
        migrateLegacy(context)
        val db = readableDatabase
        val result = ArrayList<Session>()
        db.rawQuery("SELECT id,title,updated_at FROM sessions WHERE is_active=0 ORDER BY updated_at DESC LIMIT $MAX_CHATS", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                result.add(Session(id, c.getLong(2), c.getString(1), readMessages(db, id)))
            }
        }
        return result
    }

    @Synchronized
    fun deleteArchived(context: Context, timestamp: Long, title: String): Boolean {
        migrateLegacy(context)
        val db = writableDatabase
        val deleted = db.delete("sessions", "is_active=0 AND updated_at=? AND title=?", arrayOf(timestamp.toString(), title))
        return deleted > 0
    }

    @Synchronized
    fun deleteArchived(context: Context, timestamp: Long): Boolean {
        migrateLegacy(context)
        return writableDatabase.delete("sessions", "is_active=0 AND updated_at=?", arrayOf(timestamp.toString())) > 0
    }

    @Synchronized
    fun clearArchived(context: Context): Boolean {
        migrateLegacy(context)
        return writableDatabase.delete("sessions", "is_active=0", null) >= 0
    }

    @Synchronized
    fun clearAll(context: Context): Boolean {
        migrateLegacy(context)
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.delete("sessions", null, null)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ACTIVE_ID).remove(ACTIVE_KEY).commit()
            db.setTransactionSuccessful()
            true
        } catch (_: Exception) { false } finally { db.endTransaction() }
    }

    @Synchronized
    fun clear(context: Context) {
        migrateLegacy(context)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("sessions", "is_active=1", null)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ACTIVE_ID).remove(ACTIVE_KEY).commit()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun restoreActive(context: Context, transcript: List<Pair<String, String>>) = saveActive(context, transcript)

    private fun insertSession(db: SQLiteDatabase, id: String, title: String, timestamp: Long, active: Boolean, turns: List<Pair<String, String>>) {
        db.insertWithOnConflict("sessions", null, ContentValues().apply {
            put("id", id); put("title", title); put("created_at", timestamp); put("updated_at", timestamp); put("is_active", if (active) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        val now = System.currentTimeMillis()
        turns.forEachIndexed { index, pair -> insertMessage(db, id, pair.first, pair.second, now + index, index) }
    }

    private fun insertMessage(db: SQLiteDatabase, sessionId: String, role: String, content: String, createdAt: Long, order: Int) {
        db.insert("messages", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("session_id", sessionId); put("role", role)
            put("content", content.take(6000)); put("created_at", createdAt); put("sort_order", order)
        })
    }

    private fun readMessages(db: SQLiteDatabase, id: String): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        db.rawQuery("SELECT role,content FROM messages WHERE session_id=? ORDER BY sort_order ASC", arrayOf(id)).use { c ->
            while (c.moveToNext()) result.add(c.getString(0) to c.getString(1))
        }
        return result
    }

    private fun readLegacyMessages(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val role = o.optString("kind")
                val content = o.optString("text").take(6000)
                if ((role == "me" || role == "ai") && content.isNotBlank()) role to content else null
            }.takeLast(MAX_TURNS)
        } catch (_: Exception) { emptyList() }
    }

    private fun titleFor(turns: List<Pair<String, String>>) =
        turns.firstOrNull { it.first == "me" }?.second?.lineSequence()?.firstOrNull()?.trim()?.take(64)
            ?.ifBlank { "Conversation" } ?: "Conversation"

    private fun sessionExists(db: SQLiteDatabase, id: String): Boolean =
        db.rawQuery("SELECT 1 FROM sessions WHERE id=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }

    companion object {
        private const val PREFS = "orion_chat_history"
        private const val ACTIVE_KEY = "active_transcript"
        private const val ARCHIVE_KEY = "archived_chats"
        private const val ACTIVE_ID = "active_session_id"
        private const val MIGRATED = "sqlite_sessions_migrated_v1"
        private const val MAX_TURNS = 80
        private const val MAX_CHATS = 100
        @Volatile private var instance: OrionConversationRepository? = null

        fun get(context: Context): OrionConversationRepository =
            instance ?: synchronized(this) {
                instance ?: OrionConversationRepository(context).also { instance = it }
            }
    }
}
