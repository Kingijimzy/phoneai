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
        CHAT(true), COMMAND(true), VOICE(true), SPECIALIST(true), TRADING(true), AUTONOMY(false), INITIATIVE(false), WORKER(false)
    }
    data class Entry(val at: Long, val surface: Surface, val summary: String, val admitted: Boolean)

    /** Typed Talk/Command requests share execution but retain their actual source surface. */
    fun surfaceForTypedRequest(conversationOnly: Boolean): Surface =
        if (conversationOnly) Surface.CHAT else Surface.COMMAND

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

    /** Unknown master-control state is not permission to execute. */
    internal fun shouldAdmit(masterEnabled: Boolean?): Boolean = masterEnabled == true

    /** Admission: the master switch is the only gate here; everything else is decided inside the agent. */
    fun admit(c: Context, surface: Surface, text: String): Boolean {
        val masterEnabled = runCatching { OrionMasterControl.isEnabled(c) }.getOrNull()
        val ok = shouldAdmit(masterEnabled)
        record(surface, text, ok)
        if (ok && surface.ownerInitiated) {
            runCatching {
                c.getSharedPreferences("phoneai", Context.MODE_PRIVATE).edit()
                    .putLong("owner_last_active", System.currentTimeMillis()).apply()
                OrionDrives.onOwnerActive(c)
                OrionIntentCompiler.record(c, text)
            }
        }
        runCatching { AgentPolicy.log(c, "coordinator", surface.name.lowercase(), ok, text.take(80)) }
        return ok
    }

    /** Run [block] as one admitted request from [surface]; returns null (and does not run it) when the brain is shut down. */
    fun <T> run(c: Context, surface: Surface, text: String, ownerId: String? = null, onCancel: (() -> Unit)? = null,
                evidence: (() -> String)? = null, block: () -> T): T? {
        if (!admit(c, surface, text)) return null
        val envelope = runCatching { OrionAdaptivePlan.assess(c) }.getOrNull()
        // A mission is only a top-level one when it comes from a real surface; spawned work attaches under the objective that asked.
        val mission = OrionMissionControl.begin(surface.name.lowercase(), text, parentId = OrionMissionControl.currentId(), mode = envelope?.mode?.name ?: "UNASSESSED")
        val owner = OrionMissionControl.Owner(ownerId ?: ("brain-" + surface.name.lowercase()), if (ownerId != null) OrionMissionControl.OwnerKind.WORKER else OrionMissionControl.OwnerKind.BRAIN)
        OrionMissionControl.claim(mission.id, owner, onCancel)
        OrionToolGuard.reset()
        OrionEntityLoop.begin(surface.name.lowercase())
        OrionEntityLoop.mark(OrionEntityLoop.Stage.PLAN)
        var ok = false
        try {
            val out = OrionMissionControl.within(mission.id) {
                // Retrieve before act: assemble the one shared context first; the turn then reuses this snapshot.
                OrionTurnContext.withTurn(c, text.ifBlank { "turn" }) {
                    OrionEntityLoop.mark(OrionEntityLoop.Stage.ACT); holdAwake(c, block)
                }
            }
            ok = true
            return out
        } finally {
            OrionEntityLoop.mark(OrionEntityLoop.Stage.LEARN); OrionEntityLoop.mark(OrionEntityLoop.Stage.REPORT)
            OrionEntityLoop.end()
            // The front door has no verifier of its own: finishing without evidence leaves the mission awaiting verification, never "done".
            OrionMissionControl.finish(mission.id, ok, verified = false, evidence = runCatching { evidence?.invoke() }.getOrNull()?.takeIf { it.isNotBlank() } ?: if (ok) "reply produced; completion not independently verified" else "run ended with an error")
        }
    }

    /** Long jobs keep the CPU awake (bounded) so Doze cannot freeze a build, install or research run mid-way. */
    private fun <T> holdAwake(c: Context, block: () -> T): T {
        val lock = runCatching {
            (c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "orion:task").also { it.acquire(30 * 60 * 1000L) }
        }.getOrNull()
        try { return block() } finally { runCatching { if (lock?.isHeld == true) lock.release() } }
    }

    fun summary(): String {
        val r = recent(20)
        if (r.isEmpty()) return ""
        return "COORDINATOR (recent requests by surface): " + surfaceCounts(r).entries.joinToString { "${it.key.name.lowercase()}=${it.value}" } +
            OrionMissionControl.summary().let { if (it.isBlank()) "" else "\n$it" }
    }
}
