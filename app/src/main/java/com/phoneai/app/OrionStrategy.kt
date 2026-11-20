package com.phoneai.app

import org.json.JSONObject

/**
 * Strategy simulation from measured evidence, not guesses: for a class of work, each candidate route is scored by its
 * observed success rate and speed in this session (OrionForesight's ledger), then adjusted by the live WorldState
 * (offline removes network routes, low battery removes heavy ones). The ranked result is what Orion is told before it
 * chooses a tool, and it names the next-best route when one is blocked, so a failure becomes a switch, not a stall.
 */
internal object OrionStrategy {
    enum class Work { RESEARCH, CODE, DEVICE, LONG_RUNNING }

    /** Candidate routes per class, best prior first. Priors only break ties until real outcomes exist. */
    private val routes = mapOf(
        Work.RESEARCH to listOf("research_web", "live_web_search", "fetch_url", "ask_agent"),
        Work.CODE to listOf("dev_shell", "self_edit", "extension_test", "spawn_worker"),
        Work.DEVICE to listOf("read_screen", "device_operation", "look", "ask_agent"),
        Work.LONG_RUNNING to listOf("spawn_worker", "dev_shell", "ask_agent")
    )
    private val network = setOf("research_web", "live_web_search", "fetch_url", "self_edit")
    private val heavy = setOf("dev_shell", "spawn_worker", "self_edit", "extension_test")

    data class Route(val tool: String, val successPct: Int, val avgSeconds: Int, val note: String)

    /** Pure core: [stats] maps tool -> (ok, failed, totalMs). Unknown tools get a neutral prior. */
    fun rank(work: Work, world: JSONObject, stats: Map<String, Triple<Int, Int, Long>>): List<Route> {
        val offline = world.optString("network") == "offline"
        val battery = world.optJSONObject("battery")
        val lowPower = (battery?.optInt("percent", 100) ?: 100) in 0..14 && battery?.optBoolean("charging", true) == false
        val workers = world.optJSONObject("agents")?.optJSONObject("workers")
        val swarmFull = workers != null && workers.optInt("running") >= workers.optInt("max", 3)
        return (routes[work] ?: emptyList()).mapIndexedNotNull { i, tool ->
            if (offline && tool in network) return@mapIndexedNotNull null
            if (lowPower && tool in heavy) return@mapIndexedNotNull null
            if (tool == "spawn_worker" && swarmFull) return@mapIndexedNotNull null
            val (ok, failed, ms) = stats[tool] ?: Triple(0, 0, 0L)
            val success = ((ok + 1.0) / (ok + failed + 2.0) * 100).toInt() - i  // Laplace-smoothed; prior order breaks ties
            val secs = if (ok + failed > 0) (ms / (ok + failed) / 1000).toInt() else 0
            Route(tool, success, secs, if (ok + failed == 0) "no evidence yet" else "$ok ok / $failed failed")
        }.sortedWith(compareByDescending<Route> { it.successPct }.thenBy { it.avgSeconds })
    }

    fun rank(work: Work, world: JSONObject): List<Route> = rank(work, world, OrionForesight.stats())

    /** The next-best route after [blocked], for the message that replaces a dead end. */
    fun nextBest(blocked: String, world: JSONObject): String? {
        val work = routes.entries.firstOrNull { blocked in it.value }?.key ?: return null
        return rank(work, world).firstOrNull { it.tool != blocked }?.let { "Next best route: ${it.tool} (${it.successPct}% expected, ${it.note})." }
    }

    fun summary(world: JSONObject): String = Work.values().joinToString("\n") { w ->
        val r = rank(w, world)
        "${w.name.lowercase()}: " + if (r.isEmpty()) "no route available right now" else r.take(3).joinToString(" > ") { "${it.tool} ${it.successPct}%" }
    }
}
