package com.phoneai.app

import android.content.Context
import org.json.JSONArray

/**
 * Self-diagnosis of Orion's own toolset: what is new since it last looked, what disappeared, what it has never
 * tried, and what keeps failing. Runs in the soul watcher so Orion notices changes to itself instead of being told.
 */
internal object OrionCapabilityAwareness {
    data class State(val added: List<String>, val removed: List<String>, val untested: List<String>, val failing: List<String>)

    private const val PREFS = "orion_capability_awareness"
    private const val KEY = "known"

    fun diff(known: Set<String>?, now: Set<String>): Pair<List<String>, List<String>> =
        if (known == null) emptyList<String>() to emptyList() // first look: record the baseline, nothing is "new"
        else (now - known).sorted() to (known - now).sorted()

    private fun registered(): Set<String> = runCatching { ToolRegistry.all.map { it.name }.toSet() }.getOrDefault(emptySet())

    private fun known(c: Context): Set<String>? =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { s ->
            runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrNull()
        }

    /** Read-only view (used by the gate and the prompt). */
    fun state(c: Context): State {
        val now = registered()
        val (added, removed) = diff(known(c), now)
        val used = runCatching { OrionSelfModel.health(c).map { it.tool }.toSet() }.getOrDefault(emptySet())
        return State(added, removed, (now - used).sorted(), runCatching { OrionSelfModel.degraded(c).map { it.tool } }.getOrDefault(emptyList()))
    }

    /** Watcher step: notice changes, remember them as lessons and follow-ups, then update the baseline. */
    fun scan(c: Context): State {
        val s = state(c)
        if (s.added.isNotEmpty()) {
            OrionLessons.learn(c, "I gained tools: ${s.added.take(8).joinToString()}. Use them when they fit instead of old workarounds.")
            OrionFollowUpStore.add(c, "New tools appeared in me (${s.added.take(5).joinToString()}): try one on a real task and note how it behaves.")
        }
        if (s.removed.isNotEmpty()) {
            OrionLessons.learn(c, "These tools no longer exist in me: ${s.removed.take(8).joinToString()}. Do not call them; use another route.")
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, JSONArray(registered().toList()).toString()).apply()
        return s
    }

    fun promptBlock(c: Context): String {
        val s = state(c)
        val parts = buildList {
            if (s.added.isNotEmpty()) add("new since I last checked: ${s.added.take(8).joinToString()}")
            if (s.removed.isNotEmpty()) add("gone: ${s.removed.take(8).joinToString()}")
            if (s.failing.isNotEmpty()) add("failing now: ${s.failing.take(5).joinToString()}")
            if (s.untested.size >= 3) add("never tried by me: ${s.untested.size} tools (e.g. ${s.untested.take(4).joinToString()})")
        }
        return if (parts.isEmpty()) "" else "SELF-DIAGNOSIS (my own toolset): " + parts.joinToString("; ") +
            ". Missing a capability the task needs? Build it (python_exec, extension_create, self_edit) rather than reporting a gap."
    }
}
