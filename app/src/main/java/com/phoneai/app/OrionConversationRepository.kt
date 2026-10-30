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
class OrionConversationRepository private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "orion_conversations.db", null, 3) {

    data class ChatEvent(val id: String, val sessionId: String, val messageId: String?, val type: String, val status: String, val summary: String, val createdAt: Long)

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
        createEventsTable(db)
        createObjectiveEvidenceTable(db)
        createSearchIndex(db, true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createEventsTable(db)
        if (oldVersion < 3) { createObjectiveEvidenceTable(db); createSearchIndex(db, true) }
    }

    private fun createEventsTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS chat_events (
            id TEXT PRIMARY KEY NOT NULL,
            session_id TEXT NOT NULL,
            message_id TEXT,
            event_type TEXT NOT NULL,
            status TEXT NOT NULL,
            summary TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS chat_events_session_time ON chat_events(session_id, created_at)")
    }

    data class SearchHit(val sessionId: String, val turnId: String, val role: String, val timestamp: Long, val title: String, val snippet: String, val before: String, val after: String)
    data class ObjectiveEvidence(val id: String, val sessionId: String, val turnId: String?, val objectiveId: String, val tool: String, val outcome: String, val summary: String, val createdAt: Long)

    private fun createObjectiveEvidenceTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS objective_evidence (id TEXT PRIMARY KEY NOT NULL, session_id TEXT NOT NULL, turn_id TEXT, objective_id TEXT NOT NULL, tool TEXT NOT NULL, outcome TEXT NOT NULL, summary TEXT NOT NULL, created_at INTEGER NOT NULL, FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS objective_evidence_objective_time ON objective_evidence(objective_id, created_at)")
    }

    /** FTS4 works on a wider range of Android SQLite versions than FTS5. */
    private fun createSearchIndex(db: SQLiteDatabase, rebuild: Boolean) {
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts4(role, content, session_id UNINDEXED, message_id UNINDEXED)")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS messages_fts_insert AFTER INSERT ON messages BEGIN INSERT INTO messages_fts(rowid,role,content,session_id,message_id) VALUES(new.rowid,new.role,new.content,new.session_id,new.id); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS messages_fts_delete AFTER DELETE ON messages BEGIN DELETE FROM messages_fts WHERE rowid=old.rowid; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS messages_fts_update AFTER UPDATE ON messages BEGIN DELETE FROM messages_fts WHERE rowid=old.rowid; INSERT INTO messages_fts(rowid,role,content,session_id,message_id) VALUES(new.rowid,new.role,new.content,new.session_id,new.id); END")
        if (rebuild) { db.delete("messages_fts", null, null); db.execSQL("INSERT INTO messages_fts(rowid,role,content,session_id,message_id) SELECT rowid,role,content,session_id,id FROM messages") }
    }
    private fun sanitizeEventSummary(value: String): String {
        var clean = value.replace(Regex("[\\r\\n\\t]+"), " ").take(1200)
        clean = clean.replace(Regex("(?i)(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password|passwd|secret)\\s*[:=]\\s*[^ ,;]+"), "\$1=[redacted]")
        clean = clean.replace(Regex("(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*"), "Bearer [redacted]")
        clean = clean.replace(Regex("\\bsk-[A-Za-z0-9_-]{12,}\\b"), "[redacted-key]")
        return clean.take(500)
    }

    /** Store bounded, secret-free lifecycle evidence for tools and approvals in the active session. */
    @Synchronized
    fun recordEvent(context: Context, messageId: String?, type: String, status: String, summary: String): ChatEvent? {
        migrateLegacy(context)
        val db = writableDatabase
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var sessionId = db.rawQuery("SELECT id FROM sessions WHERE is_active=1 ORDER BY updated_at DESC LIMIT 1", null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (sessionId == null) {
            sessionId = prefs.getString(ACTIVE_ID, null)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            insertSession(db, sessionId, "New chat", now, true, emptyList())
            prefs.edit().putString(ACTIVE_ID, sessionId).commit()
        }
        val event = ChatEvent(UUID.randomUUID().toString(), sessionId!!, messageId?.take(128),
            type.take(48), status.take(32), sanitizeEventSummary(summary), System.currentTimeMillis())
        val values = ContentValues().apply {
            put("id", event.id); put("session_id", event.sessionId); put("message_id", event.messageId)
            put("event_type", event.type); put("status", event.status); put("summary", event.summary); put("created_at", event.createdAt)
        }
        if (db.insert("chat_events", null, values) < 0) return null
        return event
    }

    /** Search active and archived sessions locally; every result includes stable session/turn references. */
    @Synchronized
    /** Verify provenance against an actual stored transcript turn before recording a decision. */
    @Synchronized
    fun findTranscriptEvidence(context: Context, sourceRole: String, excerpt: String, turnId: String? = null): Pair<String, String>? {
        migrateLegacy(context)
        val role = when (sourceRole.trim().lowercase()) {
            "user" -> "me"
            "assistant" -> "ai"
            else -> return null
        }
        val needle = excerpt.trim().take(1200)
        if (needle.length < 12) return null
        val sql = if (turnId.isNullOrBlank())
            "SELECT session_id,id FROM messages WHERE role=? AND instr(content,?)>0 ORDER BY created_at DESC LIMIT 1"
        else
            "SELECT session_id,id FROM messages WHERE role=? AND id=? AND instr(content,?)>0 LIMIT 1"
        val args = if (turnId.isNullOrBlank()) arrayOf(role, needle) else arrayOf(role, turnId, needle)
        return readableDatabase.rawQuery(sql, args).use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getString(1) else null
        }
    }

    fun searchHistory(context: Context, query: String, limit: Int = 8): List<SearchHit> {
        migrateLegacy(context)
        val stopWords = setOf("the", "and", "for", "with", "from", "that", "this", "it", "its", "please", "continue", "finish", "complete", "remaining", "outstanding", "previous", "task", "work", "fix", "test", "verify", "everything", "last", "time", "earlier", "chat", "discussed", "talked", "about", "what", "as", "our", "where", "we", "your", "you", "actual", "history", "conversation", "conversations", "orion", "search", "tell", "incomplete", "agreed", "next")
        val terms = Regex("[\\p{L}\\p{N}_-]{2,}").findAll(query.lowercase()).map { it.value.take(48) }.filterNot { it in stopWords }.distinct().take(8).toList()
        if (terms.isEmpty()) return emptyList()
        val match = terms.joinToString(" OR ") { "\"" + it.replace("\"", "\"\"") + "\"" }
        val db = readableDatabase
        val result = ArrayList<SearchHit>()
        // FTS OR keeps recall broad; rank by the number of concrete query terms in the
        // matched turn/title before applying recency and user-turn tie-breakers.
        val contentScore = terms.joinToString(" + ") { "CASE WHEN instr(lower(m.content), ?) > 0 THEN 2 ELSE 0 END" }
        val titleScore = terms.joinToString(" + ") { "CASE WHEN instr(lower(s.title), ?) > 0 THEN 1 ELSE 0 END" }
        val sql = "SELECT f.session_id,f.message_id,f.role,m.created_at,s.title,m.content,m.sort_order FROM messages_fts f JOIN messages m ON m.id=f.message_id JOIN sessions s ON s.id=f.session_id WHERE messages_fts MATCH ? ORDER BY ($contentScore + $titleScore) DESC, CASE WHEN f.role='me' OR f.role='user' THEN 0 ELSE 1 END, m.created_at DESC LIMIT ?"
        val queryArgs = ArrayList<String>().apply {
            add(match)
            terms.forEach { add(it) }
            terms.forEach { add(it) }
            add(limit.coerceIn(1, 12).toString())
        }
        db.rawQuery(sql, queryArgs.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val sid = c.getString(0); val turn = c.getString(1); val order = c.getInt(6)
                var before = ""; var after = ""
                db.rawQuery("SELECT sort_order,role,content FROM messages WHERE session_id=? AND sort_order BETWEEN ? AND ? ORDER BY sort_order", arrayOf(sid,(order-1).toString(),(order+1).toString())).use { around ->
                    while (around.moveToNext()) {
                        val line = around.getString(1) + ": " + around.getString(2).take(420)
                        if (around.getInt(0) < order) before = line
                        if (around.getInt(0) > order) after = line
                    }
                }
                val content = c.getString(5)
                val lower = content.lowercase()
                val matchAt = terms.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
                val start = (matchAt - 100).coerceAtLeast(0)
                val end = (start + 360).coerceAtMost(content.length)
                val snippet = (if (start > 0) "…" else "") + content.substring(start, end) + if (end < content.length) "…" else ""
                result += SearchHit(sid,turn,c.getString(2),c.getLong(3),c.getString(4),snippet,before,after)
            }
        }
        return result
    }

    /** Most recent conversation turns, used only when a vague reference has no searchable terms. */
    @Synchronized
    fun recentHistory(context: Context, limit: Int = 8): List<SearchHit> {
        migrateLegacy(context)
        val db = readableDatabase
        val rows = ArrayList<SearchHit>()
        val sql = "SELECT m.session_id,m.id,m.role,m.created_at,s.title,m.content FROM messages m JOIN sessions s ON s.id=m.session_id WHERE m.session_id=(SELECT id FROM sessions ORDER BY updated_at DESC LIMIT 1) ORDER BY m.sort_order DESC LIMIT ?"
        db.rawQuery(sql, arrayOf(limit.coerceIn(1, 12).toString())).use { c ->
            while (c.moveToNext()) rows += SearchHit(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getString(4),c.getString(5).take(600),"","")
        }
        return rows
    }

    /** Record the actual executor result against the active session and objective ID. */
    @Synchronized
    fun recordObjectiveEvidence(context: Context, objectiveId: String, tool: String, outcome: String, summary: String, turnId: String? = null): ObjectiveEvidence? {
        if (objectiveId.isBlank() || tool.isBlank()) return null
        migrateLegacy(context)
        val db = writableDatabase
        val sid = db.rawQuery("SELECT id FROM sessions WHERE is_active=1 ORDER BY updated_at DESC LIMIT 1", null).use { if (it.moveToFirst()) it.getString(0) else null } ?: return null
        val sourceTurnId = turnId?.take(128) ?: db.rawQuery("SELECT id FROM messages WHERE session_id=? AND role!=? ORDER BY sort_order DESC LIMIT 1", arrayOf(sid, "ai")).use { if (it.moveToFirst()) it.getString(0) else null }
        val evidence = ObjectiveEvidence(UUID.randomUUID().toString(),sid,sourceTurnId,objectiveId.take(32),tool.take(80),outcome.take(24),sanitizeEventSummary(summary),System.currentTimeMillis())
        val values = ContentValues().apply { put("id",evidence.id); put("session_id",sid); put("turn_id",evidence.turnId); put("objective_id",evidence.objectiveId); put("tool",evidence.tool); put("outcome",evidence.outcome); put("summary",evidence.summary); put("created_at",evidence.createdAt) }
        return if (db.insert("objective_evidence",null,values) >= 0) evidence else null
    }

    @Synchronized
    fun objectiveEvidence(context: Context, objectiveId: String): List<ObjectiveEvidence> {
        migrateLegacy(context)
        val out = ArrayList<ObjectiveEvidence>()
        readableDatabase.rawQuery("SELECT id,session_id,turn_id,objective_id,tool,outcome,summary,created_at FROM objective_evidence WHERE objective_id=? ORDER BY created_at",arrayOf(objectiveId)).use { c ->
            while (c.moveToNext()) out += ObjectiveEvidence(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6),c.getLong(7))
        }
        return out
    }

    @Synchronized
    fun eventsForActive(context: Context): List<ChatEvent> {
        migrateLegacy(context)
        val db = readableDatabase
        val id = db.rawQuery("SELECT id FROM sessions WHERE is_active=1 ORDER BY updated_at DESC LIMIT 1", null).use {
            if (it.moveToFirst()) it.getString(0) else return emptyList()
        }
        val result = ArrayList<ChatEvent>()
        db.rawQuery("SELECT id,session_id,message_id,event_type,status,summary,created_at FROM chat_events WHERE session_id=? ORDER BY created_at ASC", arrayOf(id)).use { c ->
            while (c.moveToNext()) result.add(ChatEvent(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getLong(6)))
        }
        return result
    }

    private fun clean(turns: List<Pair<String, String>>) =
        turns.filter { (kind, text) -> (kind == "me" || kind == "ai") && text.isNotBlank() }
            .takeLast(MAX_TURNS).map { it.first to it.second.take(6000) }

    @Synchronized
    fun migrateLegacy(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MIGRATED, false)) return
        val db = writableDatabase
        db.beginTransaction()
        var transactionCommitted = false
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
            transactionCommitted = true
        } finally {
            db.endTransaction()
        }
        if (transactionCommitted) prefs.edit().putBoolean(MIGRATED, true).commit()
    }

    @Synchronized
    fun activeSessionId(context: Context): String? {
        migrateLegacy(context)
        return readableDatabase.rawQuery("SELECT id FROM sessions WHERE is_active=1 ORDER BY updated_at DESC LIMIT 1", null).use {
            if (it.moveToFirst()) it.getString(0) else null
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
            val sessionId = id ?: UUID.randomUUID().toString().also { freshId ->
                prefs.edit().putString(ACTIVE_ID, freshId).commit()
                insertSession(db, freshId, titleFor(turns), System.currentTimeMillis(), true, emptyList())
            }
            val now = System.currentTimeMillis()
            val title = titleFor(turns)
            db.update("sessions", ContentValues().apply {
                put("title", title)
                put("updated_at", now)
            }, "id=?", arrayOf(sessionId))
            val preservedIds = HashMap<Int, String>()
            db.rawQuery("SELECT id,role,content,sort_order FROM messages WHERE session_id=?", arrayOf(sessionId)).use { rows ->
                while (rows.moveToNext()) {
                    val order = rows.getInt(3)
                    if (order in turns.indices && rows.getString(1) == turns[order].first && rows.getString(2) == turns[order].second) preservedIds[order] = rows.getString(0)
                }
            }
            db.delete("messages", "session_id=?", arrayOf(sessionId))
            turns.forEachIndexed { index, pair ->
                insertMessage(db, sessionId, pair.first, pair.second, now + index, index, preservedIds[index] ?: UUID.randomUUID().toString())
            }
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
                val preservedIds = HashMap<Int, String>()
                db.rawQuery("SELECT id,role,content,sort_order FROM messages WHERE session_id=?", arrayOf(sessionId)).use { rows ->
                    while (rows.moveToNext()) {
                        val order = rows.getInt(3)
                        if (order in turns.indices && rows.getString(1) == turns[order].first && rows.getString(2) == turns[order].second) preservedIds[order] = rows.getString(0)
                    }
                }
                db.delete("messages", "session_id=?", arrayOf(sessionId))
                val now = System.currentTimeMillis()
                turns.forEachIndexed { index, pair -> insertMessage(db, sessionId, pair.first, pair.second, now + index, index, preservedIds[index] ?: UUID.randomUUID().toString()) }
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
    fun renameArchivedById(context: Context, sessionId: String, title: String): Boolean {
        migrateLegacy(context)
        val safeTitle = title.replace(Regex("\\s+"), " ").trim().take(80)
        if (safeTitle.isBlank()) return false
        val values = ContentValues().apply { put("title", safeTitle); put("updated_at", System.currentTimeMillis()) }
        return writableDatabase.update("sessions", values, "id=? AND is_active=0", arrayOf(sessionId)) == 1
    }

    @Synchronized
    fun deleteArchivedById(context: Context, sessionId: String): Boolean {
        migrateLegacy(context)
        return writableDatabase.delete("sessions", "id=? AND is_active=0", arrayOf(sessionId)) > 0
    }

    /** Switch the active pointer to the exact archived session ID; keep all other sessions intact. */
    @Synchronized
    fun restoreArchivedById(context: Context, sessionId: String): List<Pair<String, String>>? {
        migrateLegacy(context)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val exists = db.rawQuery("SELECT is_active FROM sessions WHERE id=?", arrayOf(sessionId)).use {
                it.moveToFirst() && it.getInt(0) == 0
            }
            if (!exists) return null
            db.update("sessions", ContentValues().apply { put("is_active", 0) }, "is_active=1", null)
            val changed = db.update("sessions", ContentValues().apply { put("is_active", 1); put("updated_at", System.currentTimeMillis()) },
                "id=? AND is_active=0", arrayOf(sessionId))
            if (changed != 1) return null
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putString(ACTIVE_ID, sessionId).commit()
            val messages = readMessages(db, sessionId)
            val encoded = JSONArray()
            messages.forEach { (kind, text) -> encoded.put(org.json.JSONObject().put("kind", kind).put("text", text)) }
            prefs.edit().putString(ACTIVE_KEY, encoded.toString()).commit()
            db.setTransactionSuccessful()
            return messages
        } finally { db.endTransaction() }
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

    private fun insertMessage(db: SQLiteDatabase, sessionId: String, role: String, content: String, createdAt: Long, order: Int, messageId: String = UUID.randomUUID().toString()) {
        db.insert("messages", null, ContentValues().apply {
            put("id", messageId); put("session_id", sessionId); put("role", role)
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
