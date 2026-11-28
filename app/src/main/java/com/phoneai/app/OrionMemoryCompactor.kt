package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Context compaction: raw logs (experience records, lessons, missions) can grow without bound, but the
 * model's context cannot. Once an hour the heartbeat folds that raw material plus the previous summary
 * into a ~2 KB "core summary", which is the only thing injected into prompts.
 */
internal object OrionMemoryCompactor {
    const val SUMMARY_MAX_CHARS = 2_000
    const val INTERVAL_MS = 60L * 60 * 1000
    private const val MIN_RAW_CHARS = 1_500
    private const val RAW_MAX_CHARS = 100_000
    private const val PREFS = "orion_core_summary_v1"

    fun summary(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("text", "").orEmpty()
    private fun lastRun(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("at", 0L)

    /** Pure: is a compaction due? */
    fun due(now: Long, lastRunAt: Long, rawChars: Int): Boolean =
        rawChars >= MIN_RAW_CHARS && now - lastRunAt >= INTERVAL_MS

    /** Pure: the instruction given to the summariser. */
    fun prompt(previous: String, raw: String): String =
        "Compress Orion's memory into a CORE SUMMARY of at most $SUMMARY_MAX_CHARS characters. Keep durable facts about Kingi, " +
        "standing decisions, what has been built, what keeps failing and what worked, and open commitments. Drop chatter, " +
        "duplicates and anything expired. Plain text, short lines, no preamble.\n\nPREVIOUS SUMMARY:\n" +
        previous.ifBlank { "(none)" } + "\n\nNEW RAW LOG:\n" + raw.takeLast(RAW_MAX_CHARS)

    /** Pure: trims the model output to the budget without cutting a line in half when possible. */
    fun clamp(text: String): String {
        val t = text.trim()
        if (t.length <= SUMMARY_MAX_CHARS) return t
        val cut = t.take(SUMMARY_MAX_CHARS)
        return cut.substringBeforeLast('\n', cut).ifBlank { cut }
    }

    fun rawLog(c: Context): String = buildString {
        OrionExperience.all(c).forEach { append("${it.tool} ${if (it.ok) "ok" else "FAILED"}: ${it.reason}\n") }
        OrionLessons.all(c).forEach { append("lesson: $it\n") }
        runCatching { OrionRules.all(c).forEach { append("rule: $it\n") } }
    }

    /** Runs a compaction if due. Never throws; returns true when a new summary was stored. */
    fun maybeRun(c: Context, now: Long = System.currentTimeMillis()): Boolean = runCatching {
        val raw = rawLog(c)
        if (!due(now, lastRun(c), raw.length)) return@runCatching false
        val provider = OrionModelProviderFactories.create(c, false)
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", prompt(summary(c), raw)))
        val out = clamp(provider.chat(msgs, false, 1024, 0.2, true, null))
        if (out.length < 40) return@runCatching false
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("text", out).putLong("at", now).apply()
        true
    }.getOrDefault(false)

    /**
     * This block is in every single prompt turn, unconditionally - there is no per-message
     * relevance filter before it's injected (unlike OrionExperience.requestBlock, which takes the
     * query). Without an explicit instruction not to, a model anchors on whatever is most
     * recently and prominently in its context and volunteers it: a casual "wassup" came back as a
     * recitation of three known bugs and a battery reading, because that's what the core summary
     * happened to hold that hour. Background recall, not a standing status report to lead with.
     */
    fun promptBlock(c: Context): String = summary(c).let {
        if (it.isBlank()) "" else "CORE SUMMARY OF YOUR LONG MEMORY (compacted hourly; trust it over guesses, " +
            "but it is background recall, not a report to recite - bring up a line from it only when this " +
            "message actually calls for that fact; a casual message still gets a short, casual reply):\n$it"
    }
}
