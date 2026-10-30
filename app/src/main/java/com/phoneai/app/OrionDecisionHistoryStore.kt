package com.phoneai.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/**
 * Local, provenance-aware decision ledger. A proposal is never promoted to an accepted
 * decision unless the caller supplies explicit user-origin evidence.
 */
internal class OrionDecisionHistoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "orion_decisions.db", null, 1) {

    data class Decision(
        val id: String, val topic: String, val statement: String, val status: String,
        val rationale: String, val rejectedAlternative: String, val rejectionReason: String,
        val sourceRole: String, val sourceSessionId: String?, val sourceTurnId: String?,
        val sourceExcerpt: String, val supersedesId: String?, val createdAt: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE decisions (
            id TEXT PRIMARY KEY NOT NULL,
            topic TEXT NOT NULL,
            statement TEXT NOT NULL,
            status TEXT NOT NULL,
            rationale TEXT NOT NULL,
            rejected_alternative TEXT NOT NULL,
            rejection_reason TEXT NOT NULL,
            source_role TEXT NOT NULL,
            source_session_id TEXT,
            source_turn_id TEXT,
            source_excerpt TEXT NOT NULL,
            supersedes_id TEXT,
            created_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX decisions_topic_time ON decisions(topic, created_at DESC)")
        db.execSQL("CREATE INDEX decisions_status_time ON decisions(status, created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun record(
        topic: String, statement: String, status: String, rationale: String,
        rejectedAlternative: String, rejectionReason: String, sourceRole: String,
        sessionId: String?, turnId: String?, sourceExcerpt: String, supersedesId: String?
    ): String {
        val safeTopic = topic.trim().take(100)
        val safeStatement = statement.trim().take(700)
        val state = status.trim().uppercase()
        val role = sourceRole.trim().lowercase()
        val evidence = sourceExcerpt.trim().take(1200)
        if (safeTopic.isBlank() || safeStatement.isBlank() || evidence.isBlank()) return "error: topic, decision, and source evidence are required."
        if (state !in setOf("PROPOSED", "ACCEPTED", "REJECTED", "UNRESOLVED", "SUPERSEDED")) return "error: status must be PROPOSED, ACCEPTED, REJECTED, UNRESOLVED, or SUPERSEDED."
        if (state in setOf("ACCEPTED", "REJECTED") && role != "user") return "error: accepted/rejected decisions require explicit user-origin evidence; assistant proposals cannot establish user agreement."
        if (state == "PROPOSED" && role != "assistant") return "error: PROPOSED records must identify assistant-origin evidence."
        if (state == "REJECTED" && (rejectedAlternative.isBlank() || rejectionReason.isBlank())) return "error: rejected decisions require the rejected approach and the reason it was rejected."
        if (state == "SUPERSEDED" && supersedesId.isNullOrBlank()) return "error: a superseding decision must identify the earlier decision ID."
        val id = UUID.randomUUID().toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (state == "SUPERSEDED") {
                val changed = db.update("decisions", ContentValues().apply { put("status", "SUPERSEDED") },
                    "id=?", arrayOf(supersedesId))
                if (changed != 1) return "error: the decision to supersede was not found."
            }
            val values = ContentValues().apply {
                put("id", id); put("topic", safeTopic); put("statement", safeStatement); put("status", state)
                put("rationale", rationale.trim().take(1200))
                put("rejected_alternative", rejectedAlternative.trim().take(700))
                put("rejection_reason", rejectionReason.trim().take(1200))
                put("source_role", role); put("source_session_id", sessionId); put("source_turn_id", turnId)
                put("source_excerpt", evidence); put("supersedes_id", supersedesId)
                put("created_at", System.currentTimeMillis())
            }
            if (db.insert("decisions", null, values) < 0) return "error: decision could not be persisted."
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return "Recorded decision [$id] as $state for '$safeTopic'."
    }

    @Synchronized
    fun search(query: String, limit: Int = 12): List<Decision> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val pattern = "%" + q.replace("%", "\\%").replace("_", "\\_") + "%"
        val out = ArrayList<Decision>()
        readableDatabase.rawQuery(
            """SELECT id,topic,statement,status,rationale,rejected_alternative,rejection_reason,
               source_role,source_session_id,source_turn_id,source_excerpt,supersedes_id,created_at
               FROM decisions WHERE topic LIKE ? ESCAPE '\\' OR statement LIKE ? ESCAPE '\\'
               OR rationale LIKE ? ESCAPE '\\' OR rejected_alternative LIKE ? ESCAPE '\\'
               OR rejection_reason LIKE ? ESCAPE '\\' OR source_excerpt LIKE ? ESCAPE '\\'
               ORDER BY CASE status WHEN 'ACCEPTED' THEN 0 WHEN 'UNRESOLVED' THEN 1
               WHEN 'REJECTED' THEN 2 WHEN 'PROPOSED' THEN 3 ELSE 4 END, created_at DESC LIMIT ?""",
            arrayOf(pattern, pattern, pattern, pattern, pattern, pattern, limit.coerceIn(1, 30).toString())
        ).use { c ->
            while (c.moveToNext()) out += Decision(
                c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(9),
                c.getString(10), c.getString(11), c.getLong(12)
            )
        }
        return out
    }

    companion object {
        @Volatile private var instance: OrionDecisionHistoryStore? = null
        fun get(context: Context): OrionDecisionHistoryStore =
            instance ?: synchronized(this) {
                instance ?: OrionDecisionHistoryStore(context).also { instance = it }
            }
    }
}
