package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Redacted debug record of tool calls. It NEVER gates, blocks or alters an action: nothing reads it
 * to decide anything, and every write is best-effort. Arguments and results pass through
 * AgentPrivacyFilter before touching disk; the store is capped at MAX_EVENTS.
 */
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
    private const val FILE = "orion_action_log.json"
    private const val MAX_EVENTS = 100
    private const val MAX_RESULT = 400
    private val lock = Any()

    fun record(context: Context, tool: String, result: String) =
        write(context, tool, "{}", result, "UNVERIFIED")

    internal fun record(
        context: Context,
        tool: String,
        args: JSONObject,
        result: String,
        verificationStatus: OrionTaskOutcome.Status
    ) = write(context, tool, AgentPrivacyFilter.safeArguments(args), result, verificationStatus.name)

    private fun write(context: Context, tool: String, safeArgs: String, result: String, status: String) {
        try {
            val safeResult = AgentPrivacyFilter.safeOutput(result).take(MAX_RESULT)
            val failed = safeResult.trimStart().startsWith("error", ignoreCase = true)
            val event = JSONObject()
                .put("at", System.currentTimeMillis())
                .put("tool", tool.take(80))
                .put("outcome", if (failed) "failed" else "ok")
                .put("failureCategory", if (failed) "error" else "")
                .put("arguments", safeArgs)
                .put("result", safeResult)
                .put("verificationStatus", status)
            synchronized(lock) {
                val file = File(context.filesDir, FILE)
                val old = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
                val out = JSONArray()
                val start = maxOf(0, old.length() - (MAX_EVENTS - 1))
                for (i in start until old.length()) out.put(old.get(i))
                out.put(event)
                file.writeText(out.toString())
            }
        } catch (_: Exception) {
        }
    }

    /** Deliberately empty: the log is a debug record and is never fed back into prompts or decisions. */
    fun recentFailureHints(context: Context, limit: Int = 4): String = ""

    /** Newest first. */
    fun all(context: Context): List<OrionActionEvent> = synchronized(lock) {
        runCatching {
            val arr = JSONArray(File(context.filesDir, FILE).readText())
            (arr.length() - 1 downTo 0).map { i ->
                val o = arr.getJSONObject(i)
                OrionActionEvent(
                    o.optLong("at"), o.optString("tool"), o.optString("outcome"),
                    o.optString("failureCategory"), o.optString("arguments"),
                    o.optString("result"), o.optString("verificationStatus")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        context.getSharedPreferences("phoneai_agent_policy", Context.MODE_PRIVATE)
            .edit().remove("action_log").apply()
    }
}
