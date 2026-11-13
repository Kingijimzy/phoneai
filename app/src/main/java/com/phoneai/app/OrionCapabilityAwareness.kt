package com.phoneai.app

import android.content.Context
import org.json.JSONArray

/**
 * Evidence-bounded self inventory: registered tools, informational pack catalog, enabled skills/extensions and configured
 * providers. It detects local inventory changes; it does not claim that a configured remote provider
 * is healthy until a real request succeeds.
 */
internal object OrionCapabilityAwareness {
    data class State(val added: List<String>, val removed: List<String>, val untested: List<String>, val failing: List<String>)

    private const val PREFS = "orion_capability_awareness"
    private const val KEY = "known"

    fun diff(known: Set<String>?, now: Set<String>): Pair<List<String>, List<String>> =
        if (known == null) emptyList<String>() to emptyList()
        else (now - known).sorted() to (known - now).sorted()

    private fun registered(context: Context): Set<String> = buildSet {
        runCatching { ToolRegistry.all.forEach { add("tool:${it.name}") } }
        runCatching { AgentPolicy.packs.filter { AgentPolicy.enabled(context, it.id) }.forEach { add("pack:${it.id}") } }
        runCatching { SkillRegistry.list(context).filter { it.enabled }.forEach { add("skill:${it.id}") } }
        runCatching {
            SelfExtendingExtensionStore(context).all().filter { it.enabled }.forEach { add("extension:${it.id}") }
        }
        runCatching {
            Providers.all.filter { it.noKey || Prefs.hasKey(context, it.id) }
                .forEach { add("provider:${it.id}") }
        }
    }

    private fun known(c: Context): Set<String>? =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { s ->
            runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrNull()
        }

    /** Read-only view (used by the gate and the prompt). */
    fun state(c: Context): State {
        val now = registered(c)
        val (added, removed) = diff(known(c), now)
        val registeredTools = runCatching { ToolRegistry.all.map { it.name }.toSet() }.getOrDefault(emptySet())
        val used = runCatching { OrionSelfModel.health(c).map { it.tool }.toSet() }.getOrDefault(emptySet())
        return State(
            added,
            removed,
            (registeredTools - used).sorted(),
            runCatching { OrionSelfModel.degraded(c).map { "tool:${it.tool}" } }.getOrDefault(emptyList())
        )
    }

    /** Watcher step: notice changes, remember them as lessons and follow-ups, then update the baseline. */
    fun scan(c: Context): State {
        val current = registered(c)
        val s = state(c)
        if (s.added.isNotEmpty()) {
            OrionLessons.learn(c, "New Orion capabilities are available: ${s.added.take(8).joinToString()}. Re-check readiness before using them.")
            OrionFollowUpStore.add(c, "New Orion capabilities appeared (${s.added.take(5).joinToString()}): verify one on a real task and record its actual readiness.")
        }
        if (s.removed.isNotEmpty()) {
            OrionLessons.learn(c, "These Orion capabilities were removed from the installed registry: ${s.removed.take(8).joinToString()}. Do not assume they remain available; select a current alternative.")
            OrionFollowUpStore.add(c, "Capability change detected: ${s.removed.take(5).joinToString()} is no longer in the active inventory. Check whether a replacement is needed.")
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(current.toList().sorted()).toString()).apply()
        return s
    }

    fun promptBlock(c: Context): String {
        val s = state(c)
        val parts = buildList {
            if (s.added.isNotEmpty()) add("new since I last checked: ${s.added.take(8).joinToString()}")
            if (s.removed.isNotEmpty()) add("removed from installed registry: ${s.removed.take(8).joinToString()}")
            if (s.failing.isNotEmpty()) add("degraded tools: ${s.failing.take(5).joinToString()}")
            if (s.untested.size >= 3) add("registered tools with no recorded use yet: ${s.untested.size} (e.g. ${s.untested.take(4).joinToString()})")
        }
        return if (parts.isEmpty()) "" else "SELF-DIAGNOSIS (local capability inventory): " + parts.joinToString("; ") +
            ". Configured providers are not necessarily live or healthy; verify them with an actual provider result. Missing a capability the task needs? Reuse a current tool or build/test a bounded extension rather than inventing availability."
    }

    /** What can actually be done right now: configured providers, phone control, failing tools. Facts, not claims. */
    fun availability(c: Context): String = runCatching {
        val ready = Providers.all.filter { it.noKey || Prefs.hasKey(c, it.id) }.map { it.id }
        val phone = PhoneAiService.instance != null
        val failing = runCatching { OrionSelfModel.degraded(c).map { it.tool } }.getOrDefault(emptyList())
        "providers_ready=${ready.take(6).joinToString().ifBlank { "none" }}; phone_control=${if (phone) "connected" else "unavailable (accessibility service not connected)"}" +
            (if (failing.isNotEmpty()) "; failing_tools=${failing.take(4).joinToString()}" else "")
    }.getOrDefault("")
}
