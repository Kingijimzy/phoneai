package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The durable half of "don't repeat a mistake": OrionRetryCoach only sees the current process, so the
 * same dead-end gets rediscovered fresh every time the app restarts (a new session, a new provider, a
 * new day). This stores a short, capped record of (tool, cause) pairs that have actually failed before,
 * across restarts, and surfaces the ones relevant to the current turn so the model sees them *before*
 * acting rather than finding out the hard way again.
 *
 * A "cause" is a short normalized signature of the error, not the full message, so near-duplicate
 * failures (same 404, different URL) collapse into one learned fact instead of growing without bound.
 */
internal object OrionFailureMemory {
    private const val PREFS = "orion_failure_memory"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 60
    private const val REPEAT_THRESHOLD = 2

    data class Entry(val tool: String, val cause: String, val count: Int, val lastSeen: Long, val fix: String)

    /** Collapses an error string to a short, stable key: first clause, numbers blanked out. */
    fun signature(raw: String): String =
        raw.trim().lowercase()
            .substringBefore('\n')
            .take(140)
            .replace(Regex("\\d+"), "#")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun load(c: Context): JSONArray = runCatching {
        JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
    }.getOrDefault(JSONArray())

    private fun save(c: Context, arr: JSONArray) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** Records a failed tool call. Returns the entry's new count (how many times this exact cause has now happened). */
    fun record(c: Context, tool: String, errorText: String): Int {
        val sig = signature(errorText)
        if (sig.isBlank()) return 0
        val arr = load(c)
        var found: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("tool") == tool && o.optString("cause") == sig) { found = o; break }
        }
        val count: Int
        if (found != null) {
            count = found.optInt("count", 1) + 1
            found.put("count", count).put("lastSeen", System.currentTimeMillis())
        } else {
            count = 1
            arr.put(JSONObject().put("tool", tool).put("cause", sig).put("count", count)
                .put("lastSeen", System.currentTimeMillis()).put("fix", ""))
            while (arr.length() > MAX_ENTRIES) arr.remove(0)
        }
        save(c, arr)
        return count
    }

    fun repeated(c: Context): List<Entry> = runCatching {
        val arr = load(c)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val count = o.optInt("count", 1)
            if (count < REPEAT_THRESHOLD) return@mapNotNull null
            Entry(o.optString("tool"), o.optString("cause"), count, o.optLong("lastSeen"), o.optString("fix"))
        }.sortedByDescending { it.count }
    }.getOrDefault(emptyList())

    /** Prompt section: only tools/causes that have genuinely recurred, newest and most frequent first. Empty when nothing has recurred yet. */
    fun section(c: Context): String {
        val entries = repeated(c).take(10)
        if (entries.isEmpty()) return ""
        return buildString {
            appendLine("KNOWN FAILURES (learned across sessions, not just this one — do not run into these again):")
            entries.forEach { e ->
                append("- ").append(e.tool).append(": \"").append(e.cause).append("\" has failed ").append(e.count).append(" times before.")
                if (e.fix.isNotBlank()) append(" What actually worked last time: ").append(e.fix)
                else append(" No confirmed fix recorded yet — try a genuinely different approach, not the same call again.")
                appendLine()
            }
        }.trim()
    }
}
