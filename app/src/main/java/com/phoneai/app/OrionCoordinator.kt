package com.phoneai.app

import android.content.Context

/**
 * The one front door of Orion's runtime. Chat, typed commands, voice, autonomy goals, Orion's own initiative,
 * specialist agents and the Trading Desk all enter here, so there is a single place that checks the master switch,
 * notes real owner activity (for the Proactive Gate), leaves one trail of who asked for what, and runs the work.
 * It owns no model and no tools of its own: it hands the request to the same Agent/AgentRunner paths, which keep
 * their own safeguards (delete/wipe confirmation, per-trade approval).
 */
internal object OrionCoordinator {
    enum class Surface(val ownerInitiated: Boolean) {
        CHAT(true), COMMAND(true), VOICE(true), SPECIALIST(true), TRADING(true), AUTONOMY(false), INITIATIVE(false)
    }
    data class Entry(val at: Long, val surface: Surface, val summary: String, val admitted: Boolean)

    private val ring = ArrayDeque<Entry>()
    private const val MAX = 40

    fun record(surface: Surface, text: String, admitted: Boolean, now: Long = System.currentTimeMillis()) {
        synchronized(ring) {
            ring.addLast(Entry(now, surface, text.replace(Regex("\\s+"), " ").take(90), admitted))
            while (ring.size > MAX) ring.removeFirst()
        }
    }

    fun recent(limit: Int = 8): List<Entry> = synchronized(ring) { ring.toList().takeLast(limit) }

    /** Pure: how many requests each surface made lately, for the self-diagnosis view. */
    fun surfaceCounts(entries: List<Entry>): Map<Surface, Int> = entries.groupingBy { it.surface }.eachCount()

    /** Admission: the master switch is the only gate here; everything else is decided inside the agent. */
    fun admit(c: Context, surface: Surface, text: String): Boolean {
        val ok = runCatching { OrionMasterControl.isEnabled(c) }.getOrDefault(true)
        record(surface, text, ok)
        if (ok && surface.ownerInitiated) {
            runCatching {
                c.getSharedPreferences("phoneai", Context.MODE_PRIVATE).edit()
                    .putLong("owner_last_active", System.currentTimeMillis()).apply()
            }
        }
        runCatching { AgentPolicy.log(c, "coordinator", surface.name.lowercase(), ok, text.take(80)) }
        return ok
    }

    /** Run [block] as one admitted request from [surface]; returns null (and does not run it) when the brain is shut down. */
    fun <T> run(c: Context, surface: Surface, text: String, block: () -> T): T? =
        if (admit(c, surface, text)) block() else null

    fun summary(): String {
        val r = recent(20)
        if (r.isEmpty()) return ""
        return "COORDINATOR (recent requests by surface): " + surfaceCounts(r).entries.joinToString { "${it.key.name.lowercase()}=${it.value}" }
    }
}
