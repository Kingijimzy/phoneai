package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Orion believes about the world, with dates. Every fact has a source and a time it stays true; past that it
 * is marked STALE and he must recheck it before stating it. He records what he learns with brain world_learn.
 */
internal object OrionWorldview {
    data class Fact(val text: String, val source: String, val learnedAt: Long, val validDays: Int)
    private const val PREFS = "orion_worldview"
    private const val MAX = 40
    private const val DAY = 86_400_000L

    private fun load(c: Context): List<Fact> = runCatching {
        val a = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("facts", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Fact(o.optString("t"), o.optString("s"), o.optLong("at"), o.optInt("d", 30)) } }
    }.getOrDefault(emptyList())

    private fun save(c: Context, list: List<Fact>) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("facts", JSONArray(list.map { JSONObject().put("t", it.text).put("s", it.source).put("at", it.learnedAt).put("d", it.validDays) }).toString()).apply()

    fun isStale(f: Fact, now: Long) = now - f.learnedAt > f.validDays * DAY

    fun learn(c: Context, text: String, source: String, validDays: Int, now: Long = System.currentTimeMillis()): String {
        val t = text.trim().replace('\n', ' ').take(240)
        if (t.length < 8) return "error: fact is too short"
        if (OrionActionLedger.looksSecret(t) || OrionActionLedger.looksSecret(source)) return "error: that looks like a secret; the worldview never holds secrets"
        val kept = load(c).filterNot { it.text.take(40).equals(t.take(40), true) }
        save(c, (kept + Fact(t, source.trim().take(80).ifBlank { "unspecified" }, now, validDays.coerceIn(1, 3650))).takeLast(MAX))
        return "ok: learned (true for about ${validDays.coerceIn(1, 3650)} days); I will recheck it after that"
    }

    fun forget(c: Context, match: String): String {
        val all = load(c)
        val left = all.filterNot { it.text.contains(match.trim(), true) }
        save(c, left)
        return "ok: forgot ${all.size - left.size} fact(s)"
    }

    fun section(c: Context, now: Long = System.currentTimeMillis()): String {
        val facts = load(c).sortedByDescending { it.learnedAt }.take(12)
        if (facts.isEmpty()) return ""
        return "WHAT I BELIEVE ABOUT THE WORLD (dated; recheck anything STALE with live_web_search before stating it):\n" + facts.joinToString("\n") {
            val age = ((now - it.learnedAt) / DAY).toInt()
            "- ${it.text} [${it.source}, ${if (age == 0) "today" else "${age}d ago"}${if (isStale(it, now)) ", STALE" else ""}]"
        }
    }

    fun run(c: Context, a: JSONObject): String = when (a.optString("action")) {
        "world_learn" -> learn(c, a.optString("text"), a.optString("source"), a.optInt("days", 30))
        "world_forget" -> forget(c, a.optString("text"))
        else -> section(c).ifBlank { "nothing recorded yet" }
    }
}
