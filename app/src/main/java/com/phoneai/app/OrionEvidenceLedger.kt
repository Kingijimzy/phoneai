package com.phoneai.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Durable, privacy-minimal evidence trail shared by every capability adapter.
 * It records what was attempted and what was actually observed; it never treats dispatch
 * acceptance as proof of goal completion.
 */
internal object OrionEvidenceLedger {
    enum class Outcome { VERIFIED, EXECUTED_UNVERIFIED, FAILED, DENIED, RECOVERY, CONTEXT_RETRIEVED }

    private const val MAX_EVENTS = 240
    private const val MAX_TEXT = 320
    private val lock = Any()
    private fun file(context: Context) = File(context.filesDir, "orion_evidence_ledger.json")

    data class Event(
        val id: String,
        val taskId: String,
        val backend: String,
        val tool: String,
        val outcome: Outcome,
        val startedAt: Long,
        val finishedAt: Long,
        val argsFingerprint: String,
        val summary: String
    )

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun safeSummary(tool: String, result: String): String {
        val privateTools = setOf("read_screen", "research_web", "fetch_url", "mcp_call",
            "telegram_inbox", "telegram_send", "sms", "type", "copy", "remember",
            "update_memory", "recall", "look", "play", "python_exec", "shell", "termux_command",
            "open_file", "find_files", "workspace_read", "workspace_write", "browser_snapshot",
            "browser_extract", "browser_find", "web_search", "live_web_search", "live_news")
        if (tool in privateTools) {
            val state = if (result.trimStart().startsWith("error", true)) "failed" else "returned"
            return "Private result omitted; $state; chars=${result.length}"
        }
        return result
            .replace(Regex("(?i)bearer\\s+[a-z0-9._~+/-]+=*"), "Bearer [redacted]")
            .replace(Regex("(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|authorization)\\s*[:=]\\s*)[^\\s,;]+")) { it.groupValues[1] + "[redacted]" }
            .replace(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE), "[email redacted]")
            .replace(Regex("\\+?\\d[\\d ()-]{7,}\\d"), "[number redacted]")
            .take(MAX_TEXT)
    }

    private fun outcome(result: String): Outcome = when {
        result.trimStart().startsWith("error", true) -> Outcome.FAILED
        result.contains("LOCAL VERIFY PASS:", true) -> Outcome.VERIFIED
        result.contains("PASS:", true) && !result.contains("UNVERIFIED:", true) && !result.contains("FAIL:", true) -> Outcome.VERIFIED
        Regex("\\b(message sent|email sent|sent successfully|delivered|read by recipient|deleted successfully|completed successfully)\\b", RegexOption.IGNORE_CASE).containsMatchIn(result) -> Outcome.VERIFIED
        else -> Outcome.EXECUTED_UNVERIFIED
    }

    /** One durable event per dispatch, with no raw arguments or private tool output persisted. */
    fun recordDispatch(
        context: Context,
        taskId: String,
        backend: String,
        tool: String,
        args: JSONObject,
        startedAt: Long,
        result: String
    ): Boolean = append(context, JSONObject()
        .put("id", digest("$startedAt|$tool|$taskId|$result").take(24))
        .put("taskId", safeTaskId(taskId))
        .put("backend", backend.take(32))
        .put("tool", tool.take(80))
        .put("outcome", outcome(result).name)
        .put("startedAt", startedAt)
        .put("finishedAt", System.currentTimeMillis())
        .put("argsFingerprint", digest(args.toString()))
        .put("summary", safeSummary(tool, result))
    )

    /**
     * Records only metadata proving that objective-scoped memory retrieval completed.
     * The objective itself and retrieved text are never persisted; only a one-way digest is kept.
     */
    fun recordMemoryRetrieval(
        context: Context,
        taskId: String,
        objective: String,
        source: String,
        retrievedChars: Int
    ): Boolean {
        if (objective.isBlank() || source != "persistent_memory_context" || retrievedChars < 0) return false
        val now = System.currentTimeMillis()
        return append(context, JSONObject()
            .put("id", digest("$now|memory_retrieval|$taskId|$objective").take(24))
            .put("taskId", safeTaskId(taskId))
            .put("backend", "memory")
            .put("tool", "retrieve_before_act")
            .put("outcome", Outcome.CONTEXT_RETRIEVED.name)
            .put("startedAt", now)
            .put("finishedAt", now)
            .put("argsFingerprint", digest(objective))
            .put("summary", "Objective-scoped memory context retrieved; source=$source; chars=$retrievedChars.")
        )
    }

    /** Records policy/capability denials that happen before the backend is entered. */
    fun recordBlocked(context: Context, taskId: String, tool: String, category: String, args: JSONObject = JSONObject()): Boolean =
        append(context, JSONObject()
            .put("id", digest(System.currentTimeMillis().toString() + "|denied|" + tool + "|" + safeTaskId(taskId)).take(24))
            .put("taskId", safeTaskId(taskId))
            .put("backend", "policy")
            .put("tool", tool.take(80))
            .put("outcome", Outcome.DENIED.name)
            .put("startedAt", System.currentTimeMillis())
            .put("finishedAt", System.currentTimeMillis())
            .put("argsFingerprint", digest(args.toString()))
            .put("summary", "Dispatch denied before execution: " + category.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(48))
        )
    /**
     * Persist a repair lifecycle receipt in the existing evidence ledger. The plan digest is
     * retained as a fingerprint only; proposal text, source code and approval credentials never
     * enter the ledger.
     */
    fun recordRepairStage(
        context: Context,
        taskId: String,
        stage: String,
        planDigest: String,
        summary: String,
        verified: Boolean = false,
        failed: Boolean = false
    ): Boolean {
        val safeStage = stage.take(32)
        if (safeStage !in setOf("PROPOSED", "SANDBOX_PASSED", "SANDBOX_FAILED", "AWAITING_OWNER",
                "OWNER_APPROVED", "OWNER_REJECTED", "APPLIED", "VERIFIED",
                "ROLLBACK_REQUIRED", "ROLLED_BACK", "ROLLBACK_FAILED")) return false
        if (!planDigest.matches(Regex("^[a-fA-F0-9]{64}$"))) return false
        val now = System.currentTimeMillis()
        val result = summary.replace(Regex("[\\r\\n\\t]+"), " ").take(MAX_TEXT)
        return append(context, JSONObject()
            .put("id", digest("$now|repair|$safeStage|$taskId|$planDigest").take(24))
            .put("taskId", safeTaskId(taskId))
            .put("backend", "repair_lifecycle")
            .put("tool", "repair_$safeStage".lowercase())
            .put("outcome", when {
                failed -> Outcome.FAILED.name
                verified -> Outcome.VERIFIED.name
                else -> Outcome.EXECUTED_UNVERIFIED.name
            })
            .put("startedAt", now)
            .put("finishedAt", now)
            .put("argsFingerprint", planDigest.lowercase())
            .put("summary", result)
        )
    }

    /** Recovery is a first-class event; it never authorizes or replays an action. */
    fun recordRecovery(context: Context, taskId: String, tool: String, strategy: String, reason: String): Boolean =
        append(context, JSONObject()
            .put("id", digest("${System.currentTimeMillis()}|recovery|$tool|$reason").take(24))
            .put("taskId", safeTaskId(taskId))
            .put("backend", "orchestrator")
            .put("tool", tool.take(80))
            .put("outcome", Outcome.RECOVERY.name)
            .put("startedAt", System.currentTimeMillis())
            .put("finishedAt", System.currentTimeMillis())
            .put("argsFingerprint", "")
            .put("summary", "Recovery selected: " + strategy.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(48) + "; reason retained only in task context.")
        )

    private fun safeTaskId(taskId: String): String =
        if (taskId.isBlank()) "unassigned" else digest(taskId).take(20)

    private fun append(context: Context, event: JSONObject): Boolean = synchronized(lock) {
        val atomic = AtomicFile(file(context))
        val root = try {
            atomic.openRead().bufferedReader().use { JSONObject(it.readText()) }
        } catch (_: java.io.FileNotFoundException) {
            JSONObject().put("version", 1).put("events", JSONArray())
        } catch (_: Exception) {
            // Do not silently overwrite a corrupt ledger: preserve it and fail closed.
            return@synchronized false
        }
        val events = root.optJSONArray("events") ?: JSONArray()
        events.put(event)
        while (events.length() > MAX_EVENTS) events.remove(0)
        root.put("version", 1).put("events", events).put("updatedAt", System.currentTimeMillis())
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            true
        } catch (_: Exception) {
            val failedStream = stream
            if (failedStream != null) runCatching { atomic.failWrite(failedStream) }
            false
        }
    }

    fun events(context: Context, taskId: String? = null): List<Event> = synchronized(lock) {
        val root = try { AtomicFile(file(context)).openRead().bufferedReader().use { JSONObject(it.readText()) } }
        catch (_: Exception) { return@synchronized emptyList() }
        val wanted = taskId?.let(::safeTaskId)
        val rows = root.optJSONArray("events") ?: JSONArray()
        (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = row.optString("taskId")
            if (wanted != null && wanted != id) return@mapNotNull null
            val outcome = runCatching { Outcome.valueOf(row.optString("outcome")) }.getOrNull() ?: return@mapNotNull null
            Event(row.optString("id"), id, row.optString("backend"), row.optString("tool"),
                outcome, row.optLong("startedAt"), row.optLong("finishedAt"),
                row.optString("argsFingerprint"), row.optString("summary"))
        }
    }

    /** Completion evidence is task-scoped and requires at least one explicit verification event. */
    fun hasVerifiedEvidence(context: Context, taskId: String): Boolean =
        events(context, taskId).any { it.outcome == Outcome.VERIFIED }
}
