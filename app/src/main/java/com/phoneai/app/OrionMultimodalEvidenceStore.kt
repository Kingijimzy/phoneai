package com.phoneai.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import java.security.MessageDigest

/** Conversation-linked evidence index. Stores provenance/references, never raw media payloads. */
internal object OrionMultimodalEvidenceStore {
    enum class Modality { IMAGE, SCREEN, AUDIO_TRANSCRIPT, VIDEO_FRAME }
    private class Db(context: Context) : SQLiteOpenHelper(context, "orion_multimodal_evidence.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE evidence (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, task_ref TEXT NOT NULL, modality TEXT NOT NULL, source TEXT NOT NULL, reference TEXT NOT NULL, digest TEXT NOT NULL, summary TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX idx_evidence_session ON evidence(session_id, created_at DESC)")
            db.execSQL("CREATE INDEX idx_evidence_task ON evidence(task_ref, created_at DESC)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    data class Evidence(
        val id: String, val sessionId: String, val taskRef: String, val modality: Modality,
        val source: String, val digest: String, val summary: String, val createdAt: Long
    )
    private fun <T> withDb(context: Context, writable: Boolean, action: (SQLiteDatabase) -> T): T {
        val helper = Db(context.applicationContext)
        return try { action(if (writable) helper.writableDatabase else helper.readableDatabase) }
        finally { helper.close() }
    }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun currentTaskRef(context: Context, sessionId: String?): String {
        val graph = runCatching { OrionObjectiveGraph.load(context) }.getOrNull()
        if (graph != null && graph.objective.isNotBlank()) {
            val activeNode = graph.nodes.firstOrNull { it.status == OrionObjectiveGraph.Status.RUNNING }?.id
            return if (activeNode.isNullOrBlank()) graph.objective.take(140)
                else (graph.objective + "#" + activeNode).take(160)
        }
        return sessionId.orEmpty()
    }

    fun record(
        context: Context, sessionId: String?, taskRef: String = currentTaskRef(context, sessionId),
        modality: Modality, source: String, reference: String = "", digest: String = "", summary: String
    ): String? {
        val session = sessionId?.trim()?.take(160).orEmpty()
        if (session.isBlank()) return null
        val safeTaskRef = taskRef.replace(Regex("[\\r\\n]+"), " ").take(160)
        val safeSource = source.filter { it.isLetterOrDigit() || it in "._-" }.take(48)
        val safeSummary = summary.replace(Regex("[\\r\\n]+"), " ").take(220)
        if (safeSource.isBlank() || safeSummary.isBlank()) return null
        val safeReference = when (modality) {
            Modality.IMAGE -> reference.takeIf { it.startsWith("content://") }?.take(1000).orEmpty()
            else -> reference.take(300)
        }
        val evidenceDigest = digest.takeIf { it.matches(Regex("[a-fA-F0-9]{32,128}")) }
            ?: hash("$modality|$safeSource|$safeReference|$safeSummary")
        val created = System.currentTimeMillis()
        val id = hash("$session|$safeTaskRef|$modality|$evidenceDigest|$created").take(32)
        val values = ContentValues().apply {
            put("id", id); put("session_id", session); put("task_ref", safeTaskRef)
            put("modality", modality.name); put("source", safeSource); put("reference", safeReference)
            put("digest", evidenceDigest); put("summary", safeSummary); put("created_at", created)
        }
        return runCatching {
            withDb(context, true) { db ->
                db.insertWithOnConflict("evidence", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                    .takeIf { it != -1L }?.let { id }
            }
        }.getOrNull()
    }

    fun forSession(context: Context, sessionId: String, limit: Int = 12): List<Evidence> {
        if (sessionId.isBlank()) return emptyList()
        return withDb(context, false) { db ->
            val out = mutableListOf<Evidence>()
            db.rawQuery(
                "SELECT id,session_id,task_ref,modality,source,digest,summary,created_at FROM evidence WHERE session_id=? ORDER BY created_at DESC LIMIT ?",
                arrayOf(sessionId.take(160), limit.coerceIn(1, 30).toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val modality = runCatching { Modality.valueOf(cursor.getString(3)) }.getOrNull() ?: continue
                    out += Evidence(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        modality, cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getLong(7))
                }
            }
            out
        }
    }

    /** Metadata only: no raw URI, media bytes, OCR text or transcript content enters the prompt. */
    fun promptBlock(context: Context, sessionId: String): String {
        val rows = forSession(context, sessionId)
        if (rows.isEmpty()) return ""
        return buildString {
            appendLine("MULTIMODAL EVIDENCE INDEX FOR THIS CONVERSATION (references only; media payloads are not in this context)")
            rows.asReversed().forEach { item ->
                appendLine("- evidence=${item.id}; task=${item.taskRef.take(80)}; modality=${item.modality}; source=${item.source}; time=${item.createdAt}; ${item.summary}")
            }
            appendLine("A listed reference proves only that an observation/attachment was recorded. Re-open the original permissioned media source before describing image or video contents. Raw audio is not retained.")
        }
    }

    fun deleteSession(context: Context, sessionId: String): Int =
        withDb(context, true) { it.delete("evidence", "session_id=?", arrayOf(sessionId.take(160))) }
    fun deleteTask(context: Context, taskRef: String): Int =
        withDb(context, true) { it.delete("evidence", "task_ref=?", arrayOf(taskRef.take(160))) }
}
