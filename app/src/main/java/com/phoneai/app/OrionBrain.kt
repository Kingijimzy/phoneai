package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One brain, many panels. This is the closed loop in data: goal -> retrieve -> plan -> tool -> verify -> learn.
 *
 *  - Capability registry (what Orion can PROVE it can do, not what its prompt claims)
 *  - Rationale log (why each action was taken, attributed to the goal and the step that led to it)
 *  - Dreaming state (background consolidation of that log into lessons Orion actually reads back)
 */
internal object OrionBrain {

    // ------------------------------------------------------------------ live capability registry

    enum class Status { PROVEN, READY, DEGRADED }

    data class Capability(
        val tool: String, val backend: String, val write: Boolean,
        val calls: Int, val failures: Int, val consecutive: Int, val lastReason: String
    ) {
        val successes get() = (calls - failures).coerceAtLeast(0)
        val status get() = classify(calls, failures, consecutive)
    }

    /** DEGRADED beats everything: two failures in a row means "do not rely on it". PROVEN needs a real success. */
    fun classify(calls: Int, failures: Int, consecutive: Int): Status = when {
        consecutive >= 2 -> Status.DEGRADED
        calls - failures > 0 -> Status.PROVEN
        else -> Status.READY
    }

    fun registry(c: Context): List<Capability> {
        val health = OrionSelfModel.health(c).associateBy { it.tool }
        return ToolRegistry.all.mapNotNull { def ->
            val route = OrionCapabilityAdapters.route(def.name) ?: return@mapNotNull null
            val h = health[def.name]
            Capability(def.name, route.backend.name.lowercase(), def.access == ToolAccess.WRITE,
                h?.calls ?: 0, h?.failures ?: 0, h?.consecutive ?: 0, h?.lastReason.orEmpty())
        }
    }

    fun summary(caps: List<Capability>): String {
        val proven = caps.count { it.status == Status.PROVEN }
        val degraded = caps.filter { it.status == Status.DEGRADED }
        return buildString {
            append("LIVE CAPABILITY REGISTRY (measured from real runs, not from this prompt): ${caps.size} tools routed, $proven proven by a successful run, ${caps.size - proven - degraded.size} registered but never exercised")
            if (degraded.isNotEmpty()) append(", DEGRADED right now: ").append(degraded.take(6).joinToString { "${it.tool} (${it.consecutive}x: ${it.lastReason.take(60)})" })
            append(". Only claim a capability as working if it is proven; otherwise try it once and report what happened. Call brain {action:\"capabilities\"} for the full table.")
        }
    }

    fun capabilityReport(c: Context, name: String?): String {
        val caps = registry(c)
        if (!name.isNullOrBlank()) {
            val cap = caps.firstOrNull { it.tool == name.trim() } ?: return "error: no routed tool named '$name'."
            return "${cap.tool} [${cap.status}] backend=${cap.backend} ${if (cap.write) "changes things" else "read-only"}; ran ${cap.calls}x, ${cap.successes} ok, ${cap.failures} failed" +
                (if (cap.lastReason.isNotBlank()) "; last failure: ${cap.lastReason}" else "") + "."
        }
        val byBackend = caps.groupBy { it.backend }.toSortedMap()
        return summary(caps) + "\n" + byBackend.entries.joinToString("\n") { (b, list) ->
            "$b: " + list.sortedBy { it.tool }.joinToString(", ") { it.tool + when (it.status) { Status.PROVEN -> "✓"; Status.DEGRADED -> "✗"; Status.READY -> "" } }
        } + "\n(✓ proven, ✗ degraded, no mark = registered, not yet exercised)"
    }

    // ------------------------------------------------------------------ rationale: why each action was taken

    data class Step(val at: Long, val goal: String, val tool: String, val why: String, val ok: Boolean, val category: String, val prevTool: String = "", val prevCategory: String = "")

    /** The reason is derived from what actually led here, so it is never invented. */
    fun whyFor(goal: String, prevTool: String?, prevOk: Boolean, prevCategory: String, tool: String): String = when {
        prevTool.isNullOrBlank() -> "first step toward: ${goal.ifBlank { "the request" }}"
        !prevOk -> "recovering: $prevTool failed (${prevCategory.ifBlank { "error" }}), so trying $tool"
        prevTool == tool -> "continuing with $tool"
        else -> "next step after $prevTool succeeded"
    }

    private const val PREFS = "orion_brain"
    private const val MAX_STEPS = 120
    private val lock = Any()
    @Volatile private var goal: String = ""
    @Volatile private var lastTool: String? = null
    @Volatile private var lastOk: Boolean = true
    @Volatile private var lastCategory: String = ""

    /** Called when a new request starts: resets the chain so the first tool is attributed to the goal itself. */
    fun beginGoal(request: String) {
        goal = ExperiencePolicy.gist(request); lastTool = null; lastOk = true; lastCategory = ""
    }

    fun recordStep(c: Context, tool: String, ok: Boolean, category: String, now: Long = System.currentTimeMillis()) {
        if (tool.isBlank()) return
        runCatching {
            val step = Step(now, goal, tool, whyFor(goal, lastTool, lastOk, lastCategory, tool), ok, category, lastTool.orEmpty(), if (lastOk) "" else lastCategory)
            lastTool = tool; lastOk = ok; lastCategory = category
            synchronized(lock) { save(c, (steps(c) + step).takeLast(MAX_STEPS)) }
        }
    }

    fun steps(c: Context): List<Step> = runCatching {
        val arr = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("steps", "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            Step(it.optLong("at"), it.optString("g"), it.optString("t"), it.optString("w"), it.optBoolean("ok"), it.optString("c"), it.optString("p"), it.optString("pc"))
        }
    }.getOrDefault(emptyList())

    private fun save(c: Context, list: List<Step>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("at", it.at).put("g", it.goal).put("t", it.tool).put("w", it.why).put("ok", it.ok).put("c", it.category).put("p", it.prevTool).put("pc", it.prevCategory)) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("steps", arr.toString()).apply()
    }

    /** Earlier actions on similar goals, with their reasons: recalled next to the other episodic memory. */
    fun promptBlock(c: Context, request: String, max: Int = 3): String {
        val gist = ExperiencePolicy.gist(request)
        if (gist.isBlank()) return ""
        val hits = steps(c).filter { MemoryRanking.similarity(it.goal, gist) >= 0.3 }.takeLast(max)
        if (hits.isEmpty()) return ""
        return "WHY I DID IT BEFORE (similar goals): " + hits.joinToString(" | ") { "${it.tool} ${if (it.ok) "worked" else "failed"} — ${it.why}" }
    }

    fun whyReport(c: Context, n: Int = 12): String {
        val list = steps(c).takeLast(n)
        if (list.isEmpty()) return "No actions recorded yet."
        return list.joinToString("\n") { "${it.tool} ${if (it.ok) "ok" else "FAILED" + if (it.category.isNotBlank()) " (${it.category})" else ""} — ${it.why}" }
    }

    // ------------------------------------------------------------------ dreaming state

    data class Dream(val lessons: List<String>, val keep: List<Step>)

    private const val KEEP_MS = 14L * 86_400_000L
    private const val DREAM_EVERY_MS = 6L * 3_600_000L
    private const val DREAM_MIN_STEPS = 15

    /**
     * Pure consolidation. Two kinds of lesson come out, both attributable to real evidence:
     *  - a recovery that worked twice or more ("when A fails with X, B worked"),
     *  - a tool that only ever failed ("X failed N times and never worked").
     * Old steps are dropped; everything newer stays.
     */
    fun consolidate(steps: List<Step>, now: Long): Dream {
        val lessons = ArrayList<String>()
        steps.filter { it.ok && it.prevTool.isNotBlank() && it.prevCategory.isNotBlank() && it.prevTool != it.tool }
            .groupBy { Triple(it.prevTool, it.prevCategory, it.tool) }
            .filter { it.value.size >= 2 }
            .forEach { (k, v) -> lessons += "When ${k.first} fails with ${k.second}, ${k.third} has worked ${v.size} times; switch to it instead of retrying." }
        steps.groupBy { it.tool }.forEach { (tool, list) ->
            if (list.size >= 3 && list.none { it.ok }) {
                val cat = list.map { it.category }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                lessons += "$tool failed ${list.size} times with no success${cat?.let { " (mostly $it)" }.orEmpty()}; use another route first."
            }
        }
        return Dream(lessons.distinct().take(6), steps.filter { now - it.at <= KEEP_MS }.takeLast(MAX_STEPS))
    }

    /** Runs from the heartbeat. Cheap when it is not time; returns a one-line summary only when it learned something. */
    fun dream(c: Context, now: Long = System.currentTimeMillis(), force: Boolean = false): String = runCatching {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = steps(c)
        if (!force && (all.size < DREAM_MIN_STEPS || now - prefs.getLong("dreamed", 0L) < DREAM_EVERY_MS)) return@runCatching ""
        val result = consolidate(all, now)
        result.lessons.forEach { OrionLessons.learn(c, it, now) }
        // Graph review goes to the owner as one follow-up, only when the findings changed since the last dream.
        val issues = runCatching { graphIssues(KnowledgeGraph.all(c)) }.getOrDefault(emptyList())
        val sig = issues.joinToString("|").hashCode().toString()
        if (issues.isNotEmpty() && prefs.getString("graphSig", "") != sig) {
            runCatching { OrionFollowUpStore.add(c, "Review knowledge graph: " + issues.take(2).joinToString("; "), now) }
            prefs.edit().putString("graphSig", sig).apply()
        }
        synchronized(lock) { save(c, result.keep) }
        prefs.edit().putLong("dreamed", now).apply()
        "Dreamed over ${all.size} actions: ${result.lessons.size} lesson(s), kept ${result.keep.size}."
    }.getOrDefault("")

    // ------------------------------------------------------------------ knowledge-graph consolidation

    /**
     * Suggestions only: the graph is built from owner-approved memories, so dreaming may notice problems but must
     * never rewrite trusted facts. Finds one-word entities that are the first word of a longer one with overlapping
     * facts ("kingi" / "kingi jim"), which are almost always the same person or thing written two ways.
     */
    fun graphIssues(facts: List<GraphFact>): List<String> {
        val entities = facts.flatMap { listOf(it.a, it.b) }.distinct()
        val out = ArrayList<String>()
        for (short in entities.filter { ' ' !in it && it.length >= 3 }) {
            for (long in entities.filter { it != short && it.startsWith("$short ") }) {
                val shared = facts.count { f -> (f.a == short || f.b == short) && facts.any { g -> g !== f && (g.a == long || g.b == long) && g.rel == f.rel } }
                val reason = if (shared > 0) "$shared matching relation(s)" else "same first word"
                out += "'$short' and '$long' may be the same entity ($reason)"
            }
        }
        val weak = facts.count { it.count <= 1 }
        if (facts.size >= 10 && weak * 10 >= facts.size * 7) out += "$weak of ${facts.size} facts were seen only once; confirm or prune them in Memory"
        return out.take(6)
    }

    fun graphReport(c: Context): String {
        val issues = runCatching { graphIssues(KnowledgeGraph.all(c)) }.getOrDefault(emptyList())
        return if (issues.isEmpty()) "Knowledge graph: nothing to consolidate." else "Knowledge graph review: " + issues.joinToString("; ") + "."
    }

    // ------------------------------------------------------------------ context on demand

    @Volatile private var deferredSections: List<String> = emptyList()
    fun rememberDeferred(sections: List<String>) { deferredSections = sections.take(40) }

    /** Reads a section that was not pushed into the prompt for this request. */
    fun contextOnDemand(name: String): String {
        if (deferredSections.isEmpty()) return "Every context section was already included for the last request."
        if (name.isBlank()) return "Available on request: " + deferredSections.joinToString(" | ") { ContextRelevance.label(it) }
        val hit = deferredSections.firstOrNull { ContextRelevance.label(it).contains(name.trim(), ignoreCase = true) }
            ?: return "error: no deferred section matches '$name'. Available: " + deferredSections.joinToString(" | ") { ContextRelevance.label(it) }
        return hit.take(12_000)
    }

    // ------------------------------------------------------------------ the tool

    fun run(c: Context, a: JSONObject): String = when (a.optString("action").ifBlank { "status" }) {
        "capabilities", "registry" -> capabilityReport(c, a.optString("name").ifBlank { null })
        "check" -> capabilityReport(c, a.optString("name").ifBlank { null })
        "why" -> whyReport(c)
        "context" -> contextOnDemand(a.optString("name"))
        "missions" -> OrionMissionControl.report()
        "speed" -> OrionSpeedTrace.report()
        "facts" -> OrionSelfFacts.block(c)
        "loop" -> OrionEntityLoop.report()
        "world" -> OrionWorldState.pretty(c)
        "job" -> OrionJobLedger.run(c, a)
        "diagnostics" -> OrionDiagnostics.report()
        "evolve" -> OrionEvolution.report(OrionForesight.stats())
        "strategy" -> "Ranked routes (measured):\n" + OrionStrategy.summary(OrionWorldState.build(c))
        "foresight" -> a.optString("name").trim().let { n ->
            if (n.isBlank()) "Predictions vs outcomes:\n" + OrionForesight.calibration()
            else OrionForesight.assess(n, OrionWorldState.build(c)).let { f -> if (f.level == OrionForesight.Level.OK) "No risk foreseen for $n right now." else "${f.level.name}: ${f.consequence}" }
        }
        "platform" -> listOf(OrionMissionControl.report(), OrionWorkers.list(c), OrionProviderHealth.report(c), OrionSpeedTrace.report(), OrionEntityLoop.report(),
            OrionActivityHub.interruptions(OrionRunLog.currentRun()).takeLast(5).joinToString("\n") { "FAILED ${it.tool}: ${it.title}" }.ifBlank { "No failures in the current run." },
            OrionAppUpdater.check(c)).joinToString("\n\n")
        "errors" -> OrionActivityHub.interruptions(OrionRunLog.currentRun()).takeLast(8).let { list ->
            if (list.isEmpty()) "No failures in the current run." else "FAILURES IN THIS RUN (read internally; fix the cause with tools, do not navigate the app to look):\n" +
                list.joinToString("\n") { "- ${it.tool}: ${it.title} :: " + OrionActivityHub.cleanOutput(it.output).take(300).replace('\n', ' ') }
        }
        "models" -> OrionModelRegistry.report(c)
        "evaluate" -> OrionModelRegistry.evaluate(c)
        "plan" -> OrionAdaptivePlan.render(OrionAdaptivePlan.assess(c))
        "providers" -> OrionProviderHealth.report(c)
        "selftest" -> OrionProviderHealth.selfTest(c) + "\n\n" + OrionQuickCheck.render(OrionQuickCheck.run(c))
        "graph" -> graphReport(c)
        "cerebro" -> OrionCerebro.status(c)
        "prometheus" -> OrionPrometheus.status(c)
        "chronos" -> OrionChronos.status(c)
        "dream" -> dream(c, force = true).ifBlank { "Nothing to consolidate yet." }
        "status" -> summary(registry(c)) + "\n" + OrionProviderHealth.report(c) + "\n" + OrionCerebro.status(c) + "\n" + OrionPrometheus.status(c) + "\n" + OrionChronos.status(c) + "\nRecent actions:\n" + whyReport(c, 5)
        else -> "error: action must be status, capabilities, check, providers, selftest, why, dream, graph, cerebro, prometheus, chronos, context, missions, plan, speed, facts, loop, models, evaluate, errors, platform, world, foresight or strategy."
    }
}
