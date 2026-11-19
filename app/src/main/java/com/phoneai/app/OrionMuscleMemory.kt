package com.phoneai.app

/**
 * Learned routes as reflexes, not footnotes.
 *
 * RouteMemoryStore keeps procedures that were independently verified. Until now they were offered as "planning hints"
 * the model could ignore. Here a route becomes a reflex when it earns it: the request is genuinely similar to a task
 * that was completed with verified evidence, the same tool sequence has worked more than once or the match is
 * very strong, and none of its tools is DEGRADED right now (capability registry). A reflex says "start with this, do
 * not deliberate", and still demands a check after every step, so a stale reflex fails safe.
 */
internal object OrionMuscleMemory {
    data class Pick(val task: String, val tools: List<String>, val similarity: Double, val timesVerified: Int)

    const val MIN_SIMILARITY = 0.5
    const val STRONG_SIMILARITY = 0.8

    fun pick(routes: List<Pair<String, List<String>>>, request: String, degraded: Set<String>): Pick? {
        if (request.isBlank()) return null
        val scored = routes.filter { it.second.isNotEmpty() && it.second.none { t -> t in degraded } }
            .map { it to MemoryRanking.similarity(it.first, request) }
            .filter { it.second >= MIN_SIMILARITY }
        if (scored.isEmpty()) return null
        val best = scored.maxByOrNull { it.second }!!
        val sameSequence = routes.count { it.second == best.first.second && MemoryRanking.similarity(it.first, request) >= MIN_SIMILARITY }
        // One verified completion is enough only for a near-identical request; otherwise the route must have repeated.
        if (sameSequence < 2 && best.second < STRONG_SIMILARITY) return null
        return Pick(best.first.first, best.first.second, best.second, sameSequence)
    }

    fun directive(p: Pick): String =
        "MUSCLE MEMORY — LEARNED ROUTE (act first)\n" +
            "A request like this was completed and independently verified before (\"${p.task.take(120)}\", ${p.timesVerified}x). " +
            "Start with the same route: ${p.tools.joinToString(" → ")}. Do not deliberate or ask about it; run the first step now. " +
            "Check each result against the live state; if a step's result disagrees with what the route expects, drop the reflex and reason it out."

    /** The reflex for this request, or "" when nothing has earned one. */
    fun block(c: android.content.Context, request: String): String = runCatching {
        val degraded = OrionBrain.registry(c).filter { it.status == OrionBrain.Status.DEGRADED }.map { it.tool }.toSet()
        pick(RouteMemoryStore.verifiedRoutes(c), request, degraded)?.let { directive(it) }.orEmpty()
    }.getOrDefault("")
}
