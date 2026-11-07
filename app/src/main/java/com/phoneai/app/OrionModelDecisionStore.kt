package com.phoneai.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/**
 * Local decision ledger for model/tool execution turns.
 *
 * Every time Orion calls an AI provider or executes a tool, a decision record is written
 * before the result is known, then updated with the outcome. Records contain:
 *
 *   tool        — the tool or model provider called
 *   arguments   — redacted call arguments (API keys, passwords, credentials stripped)
 *   result      — the returned value (or error marker)
 *   verification — LOCAL VERIFY PASS / FAIL / NOT_VERIFIED
 *   reason      — why this tool/provider was chosen and what happened
 *   provider    — model provider id (nvidia, local, groq, …)
 *   model       — model id used
 *   taskId      — the owning task, if any
 *   turnId      — the conversation turn, if any
 *
 * Nothing is written to the network; this is strictly local provenance for the activity
 * timeline and for honest failure reporting. Failed tasks keep their decision history so
 * recovery can resume from the last verified checkpoint without losing task state.
 */
internal class OrionModelDecisionStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "orion_model_decisions.db", null, 1) {

    data class Decision(
        val id: String,
        val tool: String,
        val arguments: String,
        val result: String,
        val verification: String,
        val reason: String,
        val provider: String?,
        val model: String?,
        val taskId: String?,
        val turnId: String?,
        val createdAt: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE model_decisions (
            id TEXT PRIMARY KEY NOT NULL,
            tool TEXT NOT NULL,
            arguments TEXT NOT NULL,
            result TEXT NOT NULL,
            verification TEXT NOT NULL,
            reason TEXT NOT NULL,
            provider TEXT,
            model TEXT,
            task_id TEXT,
            turn_id TEXT,
            created_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX model_decisions_tool_time ON model_decisions(tool, created_at DESC)")
        db.execSQL("CREATE INDEX model_decisions_task_time ON model_decisions(task_id, created_at DESC)")
        db.execSQL("CREATE INDEX model_decisions_verification ON model_decisions(verification, created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Record a new decision before execution. Returns the decision id. */
    @Synchronized
    fun record(
        tool: String,
        arguments: String,
        reason: String,
        provider: String? = null,
        model: String? = null,
        taskId: String? = null,
        turnId: String? = null
    ): String {
        val safeTool = tool.trim().take(120)
        val safeArgs = AgentPrivacyFilter.safeArguments(runCatching { JSONObject(arguments) }.getOrElse { JSONObject().put("arguments", arguments) }).take(2000)
        val safeReason = reason.trim().take(1200)
        if (safeTool.isBlank() || safeReason.isBlank()) return "error: tool and reason are required."
        val id = UUID.randomUUID().toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("id", id); put("tool", safeTool); put("arguments", safeArgs)
                put("result", "PENDING"); put("verification", "NOT_VERIFIED")
                put("reason", safeReason); put("provider", provider)
                put("model", model); put("task_id", taskId); put("turn_id", turnId)
                put("created_at", System.currentTimeMillis())
            }
            if (db.insert("model_decisions", null, values) < 0) return "error: decision could not be persisted."
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return id
    }

    /** Update a decision with the result and verification status after execution. */
    @Synchronized
    fun complete(
        id: String,
        result: String,
        verification: String
    ): Boolean {
        val safeResult = AgentPrivacyFilter.safeOutput(result).take(4000)
        val state = when (verification.uppercase()) {
            "PASS", "FAIL", "NOT_VERIFIED", "UNVERIFIED" -> verification.uppercase()
            else -> "NOT_VERIFIED"
        }
        val db = writableDatabase
        val changed = db.update("model_decisions",
            ContentValues().apply {
                put("result", safeResult)
                put("verification", state)
            },
            "id=?", arrayOf(id))
        return changed == 1
    }

    /** Look up decisions for a task, newest first. */
    @Synchronized
    fun forTask(taskId: String, limit: Int = 50): List<Decision> {
        val db = readableDatabase
        val out = ArrayList<Decision>()
        db.rawQuery(
            """SELECT id,tool,arguments,result,verification,reason,provider,model,task_id,turn_id,created_at
               FROM model_decisions WHERE task_id=?
               ORDER BY created_at DESC LIMIT ?""",
            arrayOf(taskId, limit.coerceIn(1, 200).toString())
        ).use { c ->
            while (c.moveToNext()) out += Decision(
                c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(9), c.getLong(10)
            )
        }
        return out
    }

    /** Search decisions by tool or reason text. */
    @Synchronized
    fun search(query: String, limit: Int = 20): List<Decision> {
        val terms = Regex("[\\p{L}\\p{N}_-]{2,}")
            .findAll(query.lowercase()).map { it.value.take(48) }.distinct().take(6).toList()
        if (terms.isEmpty()) return emptyList()
        val clauses = terms.joinToString(" OR ") {
            "(tool LIKE ? OR arguments LIKE ? OR reason LIKE ? OR result LIKE ?)"
        }
        val args = ArrayList<String>()
        terms.forEach { term -> repeat(4) { args += "%$term%" } }
        args += limit.coerceIn(1, 100).toString()
        val out = ArrayList<Decision>()
        val db = readableDatabase
        db.rawQuery(
            """SELECT id,tool,arguments,result,verification,reason,provider,model,task_id,turn_id,created_at
               FROM model_decisions WHERE $clauses
               ORDER BY created_at DESC LIMIT ?""",
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) out += Decision(
                c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(9), c.getLong(10)
            )
        }
        return out
    }

    /** Count unverified (in-flight) decisions for a task — used to detect lost state. */
    @Synchronized
    fun unverifiedCount(taskId: String): Int {
        val db = readableDatabase
        val c = db.rawQuery(
            "SELECT COUNT(*) FROM model_decisions WHERE task_id=? AND verification='NOT_VERIFIED'",
            arrayOf(taskId))
        val n = if (c.moveToFirst()) c.getInt(0) else 0
        c.close()
        return n
    }

    companion object {
        @Volatile private var instance: OrionModelDecisionStore? = null
        fun get(context: Context): OrionModelDecisionStore =
            instance ?: synchronized(this) {
                instance ?: OrionModelDecisionStore(context).also { instance = it }
            }

        internal fun resetForTests() { synchronized(this) { instance?.close(); instance = null } }
    }
}
