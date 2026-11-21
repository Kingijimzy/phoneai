package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orion's self-model and lessons: what is working, what keeps failing, and what it has learned.
 * Pure local bookkeeping (no arguments or screen contents are stored). It informs planning and
 * proactive follow-ups; it never blocks or gates a tool.
 */
object OrionSelfModel {
    private const val PREFS = "orion_self_model"
    private const val KEY = "tools"
    private const val MAX_TOOLS = 120
    private val lock = Any()

    data class ToolHealth(val tool: String, val calls: Int, val failures: Int, val consecutive: Int, val lastReason: String, val lastAt: Long, val flagged: Boolean)

    private fun load(c: Context): JSONObject =
        runCatching { JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}") }.getOrDefault(JSONObject())

    /** Record one tool outcome. Never throws. */
    fun record(c: Context, tool: String, ok: Boolean, reason: String = "", now: Long = System.currentTimeMillis()) {
        if (tool.isBlank()) return
        runCatching {
            synchronized(lock) {
                val all = load(c)
                val t = all.optJSONObject(tool) ?: JSONObject()
                t.put("calls", t.optInt("calls") + 1)
                if (ok) {
                    t.put("consecutive", 0).put("flagged", false)
                } else {
                    t.put("failures", t.optInt("failures") + 1).put("consecutive", t.optInt("consecutive") + 1)
                    t.put("lastReason", AgentPrivacyFilter.safeOutput(reason).lineSequence().firstOrNull().orEmpty().take(160))
                    t.put("lastAt", now)
                }
                all.put(tool, t)
                if (all.length() > MAX_TOOLS) {
                    val drop = all.keys().asSequence().minByOrNull { all.getJSONObject(it).optLong("lastAt") }
                    if (drop != null && drop != tool) all.remove(drop)
                }
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, all.toString()).apply()
            }
        }
    }

    fun health(c: Context): List<ToolHealth> = synchronized(lock) {
        val all = load(c)
        all.keys().asSequence().map { k ->
            val t = all.getJSONObject(k)
            ToolHealth(k, t.optInt("calls"), t.optInt("failures"), t.optInt("consecutive"), t.optString("lastReason"), t.optLong("lastAt"), t.optBoolean("flagged"))
        }.toList()
    }

    /** Tools that failed [threshold] times in a row. */
    fun degraded(c: Context, threshold: Int = 2): List<ToolHealth> = health(c).filter { it.consecutive >= threshold }.sortedByDescending { it.consecutive }

    internal fun markFlagged(c: Context, tool: String) {
        runCatching {
            synchronized(lock) {
                val all = load(c)
                all.optJSONObject(tool)?.put("flagged", true)
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, all.toString()).apply()
            }
        }
    }

    fun clear(c: Context) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
}

object OrionLessons {
    private const val PREFS = "orion_lessons"
    private const val KEY = "lines"
    private const val MAX = 30
    private val lock = Any()

    fun learn(c: Context, lesson: String, now: Long = System.currentTimeMillis()) {
        val clean = lesson.trim().replace(Regex("\\s+"), " ").take(220)
        if (clean.isBlank()) return
        runCatching {
            synchronized(lock) {
                val arr = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
                val out = JSONArray()
                for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("t") != clean) out.put(arr.get(i))
                out.put(JSONObject().put("t", clean).put("at", now))
                val trimmed = JSONArray()
                for (i in maxOf(0, out.length() - MAX) until out.length()) trimmed.put(out.get(i))
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, trimmed.toString()).apply()
            }
        }
    }

    fun all(c: Context): List<String> = synchronized(lock) {
        runCatching {
            val arr = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
            (0 until arr.length()).map { arr.getJSONObject(it).optString("t") }
        }.getOrDefault(emptyList())
    }
}

object OrionLivingLoop {
    /** Newest lessons plus currently failing tools, ready for the system prompt. Empty when there is nothing to say. */
    fun promptBlock(c: Context, maxLessons: Int = 5): String {
        val lessons = OrionLessons.all(c).takeLast(maxLessons)
        val broken = OrionSelfModel.degraded(c).take(4)
        val missions = OrionMissions.promptBlock(c)
        val drives = OrionDrives.promptBlock(c)
        if (lessons.isEmpty() && broken.isEmpty() && missions.isEmpty() && drives.isEmpty()) return ""
        return buildString {
            if (drives.isNotEmpty()) append(drives).append("\n")
            if (missions.isNotEmpty()) append(missions).append("\n")
            append("SELF-AWARENESS (from your own recent history):")
            if (broken.isNotEmpty()) {
                append("\nCurrently failing tools — avoid or work around: ").append(broken.joinToString("; ") { "${it.tool} (${it.consecutive}x: ${it.lastReason})" })
                append("\nSELF-REPAIR: you may fix these yourself. Diagnose with a read-only call, then build the missing piece — a script via python_exec/shell for anything touching files, network or the phone, or extension_create + extension_test (tested drafts activate automatically) for pure data transforms — run it, and call the original goal again. Do not just report the failure.")
            }
            if (lessons.isNotEmpty()) append("\nLessons you have learned: ").append(lessons.joinToString(" | "))
        }
    }

    /**
     * One reflection pass, run from the heartbeat. Notices tools that keep failing, learns a lesson,
     * and queues a follow-up so Orion raises it with the owner instead of failing silently again.
     * Returns what it noticed (empty when all is well). Never throws.
     */
    /** Anticipation: an event on the owner's calendar in the next three hours becomes prepared work, once, before he asks. */
    internal fun prepareForEvents(c: Context, now: Long): String {
        val soon = runCatching { OrionCalendarMonitor.upcoming(c, 3, now).filter { it.minutesFromNow in 1..180 } }.getOrDefault(emptyList())
        val have = runCatching { OrionFollowUpStore.pending(c).map { it.text } }.getOrDefault(emptyList())
        val fresh = soon.filter { e -> have.none { it.contains(e.title.take(40)) } }.take(2)
        fresh.forEach { e -> OrionFollowUpStore.add(c, "Prepare for '${e.title.take(60)}' starting in ${e.minutesFromNow} minutes: find what it needs from my notes, messages and memory, and have a two-line brief ready to give Kingi before it starts.", e.startsAtMillis - 20 * 60_000L) }
        return if (fresh.isEmpty()) "" else "Preparing for " + fresh.joinToString { it.title.take(40) }
    }

    fun tick(c: Context, now: Long = System.currentTimeMillis()): String = runCatching {
        OrionBrain.dream(c, now).takeIf { it.isNotBlank() }?.let { OrionLessons.learn(c, it, now) }
        val prepared = prepareForEvents(c, now)
        val broken = OrionSelfModel.degraded(c).filter { !it.flagged }
        if (broken.isEmpty()) return@runCatching prepared
        broken.forEach { h ->
            OrionLessons.learn(c, "${h.tool} failed ${h.consecutive} times in a row (${h.lastReason}); try a different route before using it again.", now)
            OrionFollowUpStore.add(c, "Self-repair: '${h.tool}' keeps failing (${h.lastReason}). Diagnose it. If it is a bug in your own code: self_edit read, write the patch, prove the logic in python_exec, open a PR, wait for CI, merge. Otherwise build a replacement with extension_create. Tell Kingi what you changed.".take(400), dueAt = now)
            OrionSelfModel.markFlagged(c, h.tool)
        }
        "Noticed ${broken.size} tool(s) failing repeatedly: " + broken.joinToString { it.tool }
    }.getOrDefault("")
}
