package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Privacy-filtered owner action history with a durable verification receipt. */
data class OrionActionEvent(
    val at: Long,
    val tool: String,
    val outcome: String,
    val failureCategory: String = "",
    val arguments: String = "",
    val result: String = "",
    val verificationStatus: String = "BLOCKED_OR_UNVERIFIED"
)

object ActionLogStore {
    private val lock = Any()
    private var cache: MutableList<OrionActionEvent>? = null
    private fun file(c: Context) = File(c.filesDir, "orion_action_log.json")
    private val privateTools = setOf(
        "read_screen", "research_web", "fetch_url", "mcp_call", "telegram_inbox",
        "telegram_send", "sms", "type", "copy", "remember", "update_memory", "recall",
        "look", "play", "python_exec", "shell", "termux_command", "open_file",
        "find_files", "workspace_read", "workspace_write", "browser_snapshot",
        "browser_extract", "browser_find", "web_search", "live_web_search", "live_news"
    )

    private fun safeResult(tool: String, result: String): String {
        if (tool in privateTools) {
            val state = if (result.trimStart().startsWith("error", true)) "failed" else "returned"
            return "Private result omitted; $state; chars=${result.length}"
        }
        return AgentPrivacyFilter.safeOutput(result).take(2400)
    }

    private fun load(c: Context): MutableList<OrionActionEvent> {
        cache?.let { return it }
        val items = mutableListOf<OrionActionEvent>()
        try {
            val f = file(c)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    items.add(OrionActionEvent(
                        o.optLong("at", 0L),
                        o.optString("tool", "unknown").take(48),
                        o.optString("outcome", "result returned").take(32),
                        o.optString("failureCategory", "").take(40),
                        o.optString("arguments", "").take(2400),
                        o.optString("result", "").take(2400),
                        o.optString("verificationStatus", "BLOCKED_OR_UNVERIFIED").take(40)
                    ))
                }
            }
        } catch (_: Exception) { }
        cache = items
        return items
    }

    private fun save(c: Context) {
        val arr = JSONArray()
        load(c).forEach { e ->
            arr.put(JSONObject().put("at", e.at).put("tool", e.tool).put("outcome", e.outcome)
                .put("failureCategory", e.failureCategory).put("arguments", e.arguments)
                .put("result", e.result).put("verificationStatus", e.verificationStatus))
        }
        try {
            val target = file(c)
            val temp = File(c.filesDir, "orion_action_log.json.tmp")
            temp.writeText(arr.toString())
            if (!temp.renameTo(target)) {
                target.writeText(arr.toString())
                temp.delete()
            }
        } catch (_: Exception) { }
    }

    /** Compatibility path for policy denials and legacy callers with no structured args. */
    fun record(c: Context, tool: String, result: String) =
        record(c, tool, JSONObject(), result,
            OrionTaskOutcome.actionStatus(
                verified = false,
                failed = result.trimStart().startsWith("error:", true)
            ))

    /** One action receipt: redacted args, privacy-filtered result and task-outcome verification status. */
    fun record(
        c: Context,
        tool: String,
        args: JSONObject,
        result: String,
        verificationStatus: OrionTaskOutcome.Status
    ) = synchronized(lock) {
        val failed = result.trimStart().startsWith("error:", true)
        val outcome = if (failed) "error returned" else "result returned"
        val category = if (failed) ActionLearningPolicy.classifyFailure(result) else ""
        val items = load(c)
        items.add(OrionActionEvent(
            System.currentTimeMillis(), tool.take(48), outcome, category,
            AgentPrivacyFilter.safeArguments(args),
            safeResult(tool, result),
            verificationStatus.name
        ))
        while (items.size > 200) items.removeAt(0)
        save(c)
    }

    /**
     * Privacy-minimal failure feedback for the planner. Only tool names and the generic
     * outcome label are exposed; arguments, screen text, and user content never enter prompts.
     * These are warnings to inspect/re-plan, not proof a tool is permanently broken.
     */
    fun recentFailureHints(c: Context, limit: Int = 4): String = synchronized(lock) {
        val failures = ActionLearningPolicy.recentFailures(
            load(c).map { ActionLearningRecord(it.tool, it.outcome, it.failureCategory) },
            limit
        )
        if (failures.isEmpty()) return@synchronized ""
        failures.joinToString("\n") { failure ->
            "- ${failure.tool}: last recorded attempt failed (${ActionLearningPolicy.label(failure.failureCategory)}); inspect current state and choose a meaningfully different safe approach. This is a hint, not proof the tool is still broken."
        }
    }

    fun all(c: Context): List<OrionActionEvent> = synchronized(lock) { load(c).toList().reversed() }

    fun clear(c: Context) = synchronized(lock) {
        load(c).clear()
        save(c)
    }
}
