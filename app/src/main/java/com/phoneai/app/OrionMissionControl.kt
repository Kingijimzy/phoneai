package com.phoneai.app

import java.util.concurrent.ConcurrentHashMap

/**
 * The single owner of "what Orion is working on right now".
 *
 * Before this, four things each tracked work on their own: the front door (OrionCoordinator), durable drone tasks
 * (OrionUnifiedExecutionCoordinator), background workers (OrionWorkers) and soul sub-agents. None of them knew about
 * the others, so nothing could say "this worker belongs to that objective" or stop a whole objective at once.
 *
 * Now every unit of work is a [Mission] with ONE lifecycle (the existing TaskLifecycle rules, so a mission completes
 * only after verification), ONE owner at a time (a worker id; a second claimant is refused), a parent link so child
 * work rolls up under the objective that asked for it, and one cancel path that stops the mission and everything
 * under it. It holds state only; it never runs tools and never grants permission.
 */
internal object OrionMissionControl {
    enum class OwnerKind { BRAIN, WORKER, DRONE, SUBAGENT }

    data class Owner(val id: String, val kind: OwnerKind)

    data class Mission(
        val id: String,
        val request: String,
        val surface: String,
        val parentId: String?,
        val lifecycle: TaskLifecycle,
        val owner: Owner?,
        val mode: String,
        val startedAt: Long,
        val endedAt: Long = 0L,
        val evidence: String = "",
        val outcome: String = "",
        val announced: Boolean = false
    ) {
        /** Open until finished or cancelled. A run that ended unverified or failed is closed, not left dangling. */
        val active: Boolean get() = endedAt == 0L && !lifecycle.isTerminal
    }

    private const val KEEP_FINISHED = 40
    private val missions = ConcurrentHashMap<String, Mission>()
    private val cancellers = ConcurrentHashMap<String, () -> Unit>()
    private val lock = Any()
    private var seq = 0L
    private val current = ThreadLocal<String?>()

    /** The mission the calling thread is running inside, if any. */
    fun currentId(): String? = current.get()

    /** The newest still-active top-level mission: the fallback parent when work is spawned off the originating thread. */
    fun latestActiveRoot(): String? = missions.values.filter { it.active && it.parentId == null }.maxByOrNull { it.startedAt }?.id

    /**
     * Opens a mission and walks it to EXECUTE. Capability checking is real, not ceremonial: [mode] comes from
     * [OrionAdaptivePlan], so the lifecycle records what the mission was allowed to assume when it started.
     */
    fun begin(surface: String, request: String, parentId: String? = currentId() ?: latestActiveRoot(), mode: String = "UNASSESSED", now: Long = System.currentTimeMillis()): Mission =
        synchronized(lock) {
            val id = "m" + (++seq).toString(36) + now.toString(36).takeLast(4)
            var life = TaskLifecycle()
            repeat(4) { life = life.advance() ?: life } // UNDERSTAND→PLAN→CHECK→AUTHORIZE→EXECUTE
            val m = Mission(id, request.replace(Regex("\\s+"), " ").take(160), surface, parentId?.takeIf { missions.containsKey(it) }, life, null, mode, now)
            missions[id] = m
            trim()
            m
        }

    /** One owner at a time. The same owner may re-claim; a different live owner is refused. */
    fun claim(missionId: String, owner: Owner, cancel: (() -> Unit)? = null): Boolean = synchronized(lock) {
        val m = missions[missionId] ?: return false
        if (!m.active) return false
        if (m.owner != null && m.owner != owner) return false
        missions[missionId] = m.copy(owner = owner)
        if (cancel != null) cancellers[missionId] = cancel
        true
    }

    fun release(missionId: String, owner: Owner) = synchronized(lock) {
        val m = missions[missionId] ?: return@synchronized
        if (m.owner == owner) missions[missionId] = m.copy(owner = null)
        cancellers.remove(missionId)
        Unit
    }

    /**
     * Ends a mission. Success walks EXECUTE→VERIFY→COMPLETE→RECORD only when [verified] evidence is supplied;
     * unverified success is stored as BLOCKED-for-verification rather than claimed done. Failure goes through RECOVER
     * and stays a bounded, visible state.
     */
    fun finish(missionId: String, ok: Boolean, verified: Boolean, evidence: String, now: Long = System.currentTimeMillis()): Mission? = synchronized(lock) {
        val m = missions[missionId] ?: return null
        if (!m.active) return m
        var life = m.lifecycle
        val outcome: String
        if (ok) {
            life = life.advance() ?: life // → VERIFY
            if (verified) {
                life = life.verifiedOk() ?: life
                life = life.advance() ?: life // COMPLETE
                life = life.advance() ?: life // RECORD
                outcome = "verified"
            } else {
                // Honest state: the run produced something but nothing independently confirmed it, so it never reaches COMPLETE.
                life = life.block() ?: life
                outcome = "unverified"
            }
        } else {
            life = life.fail() ?: life
            outcome = "failed"
        }
        val out = m.copy(lifecycle = life, owner = null, evidence = evidence.replace(Regex("\\s+"), " ").take(300), endedAt = now, outcome = outcome)
        missions[missionId] = out
        cancellers.remove(missionId)
        out
    }

    /** Stops a mission and every mission under it. Returns how many were cancelled. */
    fun cancel(missionId: String, now: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        val ids = ArrayList<String>()
        fun walk(id: String) { ids += id; missions.values.filter { it.parentId == id }.forEach { walk(it.id) } }
        walk(missionId)
        var n = 0
        ids.forEach { id ->
            val m = missions[id] ?: return@forEach
            if (!m.active) return@forEach
            runCatching { cancellers.remove(id)?.invoke() }
            missions[id] = m.copy(lifecycle = m.lifecycle.cancel() ?: m.lifecycle, owner = null, endedAt = now)
            n++
        }
        n
    }

    fun cancelAll(): Int = missions.values.filter { it.active && it.parentId == null }.sumOf { cancel(it.id) }

    /** Runs [block] as the current mission on this thread, so anything it spawns is attached as a child. */
    fun <T> within(missionId: String, block: () -> T): T {
        val prev = current.get()
        current.set(missionId)
        try { return block() } finally { current.set(prev) }
    }

    /**
     * What is worth interrupting the owner for: finished background work that has not been mentioned yet.
     * Returns at most one mission, newest first; the proactive engine decides whether and when it is spoken.
     */
    fun attention(now: Long = System.currentTimeMillis(), windowMs: Long = 6L * 3_600_000L): Mission? =
        missions.values.filter { !it.active && it.surface == "worker" && !it.announced && it.outcome != "" && now - it.endedAt <= windowMs && it.lifecycle.phase != TaskLifecycle.Phase.CANCELLED }
            .maxByOrNull { it.endedAt }

    fun markAnnounced(id: String) = synchronized(lock) {
        missions[id]?.let { missions[id] = it.copy(announced = true) }
        Unit
    }

    fun get(id: String): Mission? = missions[id]
    fun children(id: String): List<Mission> = missions.values.filter { it.parentId == id }.sortedBy { it.startedAt }
    fun active(): List<Mission> = missions.values.filter { it.active }.sortedBy { it.startedAt }

    private fun trim() {
        val done = missions.values.filter { !it.active }.sortedByDescending { it.endedAt }
        done.drop(KEEP_FINISHED).forEach { missions.remove(it.id) }
    }

    fun clearForTest() { missions.clear(); cancellers.clear(); seq = 0L }

    fun report(): String {
        val roots = missions.values.filter { it.parentId == null }.sortedByDescending { it.startedAt }.take(8)
        if (roots.isEmpty()) return "No missions yet."
        fun line(m: Mission, depth: Int): String =
            "  ".repeat(depth) + "- ${m.id} [${if (m.outcome.isNotBlank()) m.outcome else m.lifecycle.phase.name.lowercase()}] ${m.surface}: ${m.request.take(70)}" +
                (m.owner?.let { " · owner ${it.kind.name.lowercase()}:${it.id}" } ?: "") + (if (m.mode != "UNASSESSED") " · mode ${m.mode}" else "")
        return roots.joinToString("\n") { r -> (listOf(line(r, 0)) + children(r.id).map { line(it, 1) }).joinToString("\n") }
    }

    /** Pure rendering: the current objective plus the last finished mission's evidence, so every context carries what is true now. */
    internal fun evidenceBlock(goal: String, activeList: List<Mission>, all: Collection<Mission>): String {
        val last = all.filter { !it.active && it.endedAt > 0L }.maxByOrNull { it.endedAt }
        if (goal.isBlank() && activeList.isEmpty() && last == null) return ""
        return buildString {
            append("MISSION AND EVIDENCE (one task identity: the mission id; objective and evidence follow it)\n")
            if (goal.isNotBlank()) append("Objective: ").append(goal.take(160)).append('\n')
            activeList.take(3).forEach { append("Active ${it.id} (${it.lifecycle.phase.name.lowercase()}, ${it.surface}): ").append(it.request.take(80)).append('\n') }
            if (last != null) append("Last finished ${last.id}: ").append(last.outcome.ifBlank { "ended" }).append(" — evidence: ").append(last.evidence.ifBlank { "none recorded" }.take(160))
        }.trim()
    }

    fun evidence(goal: String): String = evidenceBlock(goal, active(), missions.values)

    fun summary(): String {
        val a = active()
        if (a.isEmpty()) return ""
        return "MISSIONS (one lifecycle, one owner per mission): ${a.size} active — " +
            a.take(5).joinToString("; ") { "${it.id} ${it.lifecycle.phase.name.lowercase()} \"${it.request.take(40)}\"" + (it.owner?.let { o -> " owned by ${o.id}" } ?: "") }
    }
}
