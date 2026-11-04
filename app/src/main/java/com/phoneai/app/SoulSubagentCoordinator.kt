package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stage 6: bounded local subagents coordinated by one parent.
 *
 * Specialists produce evidence-backed notes from existing local state; they do not dispatch tools,
 * call providers, change policy, or impersonate completed work. The parent owns ordering, budgets,
 * persistence and the final report.
 */
internal object SoulSubagentCoordinator {
    enum class Role { PLANNER, CAPABILITY_AUDITOR, SAFETY_REVIEWER, MEMORY_CURATOR }
    data class Finding(val role: Role, val status: String, val summary: String, val evidence: List<String>)
    data class RunReport(val id: String, val at: Long, val findings: List<Finding>, val summary: String)

    private const val PREFS = "orion_soul_subagents_v1"
    private const val KEY = "runs"
    private const val MAX_RUNS = 12
    private const val MAX_EVIDENCE = 5
    private const val MAX_TEXT = 220
    private const val COOLDOWN_MS = 30L * 60L * 1000L
    private val lock = Any()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun clean(s: String, max: Int = MAX_TEXT): String =
        AgentPrivacyFilter.safeCommand(s).replace(Regex("\\s+"), " ").trim().take(max)

    private fun readRuns(c: Context): JSONArray = try {
        JSONArray(prefs(c).getString(KEY, "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    private fun planner(c: Context): Finding {
        val snapshot = SoulEngineState.snapshot(c)
        val pending = snapshot.proposals.filter { it.status == SoulEngineState.ProposalStatus.PENDING }.take(5)
        if (pending.isEmpty()) return Finding(Role.PLANNER, "idle", "No pending proposals need planning.", listOf("Soul state contains no pending proposals."))
        val notes = pending.map { p ->
            val missing = p.allowedTools.filterNot { it in ToolRegistry.names() }
            "${clean(p.title, 100)}: evidence=${p.evidenceCount}, tools=${p.allowedTools.size}, missing=${missing.size}"
        }
        val blocked = pending.count { p -> p.allowedTools.isEmpty() || p.allowedTools.any { it !in ToolRegistry.names() } }
        return Finding(
            Role.PLANNER, if (blocked > 0) "needs_review" else "ready_for_review",
            "Reviewed ${pending.size} pending proposal(s); ${blocked} have empty or unavailable tool hints. No route was executed or adopted.",
            notes.take(MAX_EVIDENCE)
        )
    }

    private fun capabilityAuditor(c: Context): Finding {
        val names = runCatching { ToolRegistry.names() }.getOrDefault(emptyList())
        val packs = AgentPolicy.packs.filter { it.implemented }
        val enabled = packs.filter { AgentPolicy.enabled(c, it.id) }
        val off = packs.filterNot { AgentPolicy.enabled(c, it.id) }.take(MAX_EVIDENCE).map { it.id }
        val notes = listOf(
            "Registered tools: ${names.size}.",
            "Implemented packs: ${packs.size}; enabled packs: ${enabled.size}."
        ) + if (off.isNotEmpty()) listOf("Disabled packs (not changed): ${off.joinToString()}.") else emptyList()
        return Finding(
            Role.CAPABILITY_AUDITOR,
            if (names.isEmpty()) "blocked" else if (enabled.size < packs.size) "limited" else "ready",
            "Read-only capability inventory completed; this does not verify device permissions or service health.",
            notes.take(MAX_EVIDENCE)
        )
    }

    private fun safetyReviewer(c: Context): Finding {
        val snapshot = SoulEngineState.snapshot(c)
        val pending = snapshot.proposals.filter { it.status == SoulEngineState.ProposalStatus.PENDING }.take(5)
        val writeHints = pending.flatMap { it.allowedTools }.distinct()
            .filter { name -> ToolRegistry.all.firstOrNull { it.name == name }?.access == ToolAccess.WRITE }
        val missing = pending.flatMap { it.allowedTools }.distinct().filterNot { it in ToolRegistry.names() }
        val notes = buildList {
            add("Pending proposals reviewed: ${pending.size}.")
            if (writeHints.isNotEmpty()) add("Write-capable hints require normal policy/approval gates: ${writeHints.take(4).joinToString()}.")
            if (missing.isNotEmpty()) add("Unavailable hints remain blocked: ${missing.take(4).joinToString()}.")
            add("No permission, trusted session, allowlist, or approval state was changed.")
        }
        return Finding(
            Role.SAFETY_REVIEWER,
            if (missing.isNotEmpty()) "blocked_items_found" else if (writeHints.isNotEmpty()) "approval_sensitive" else "reviewed",
            "Reviewed proposal hints against registered access classes; this is a static check, not authorization.",
            notes.take(MAX_EVIDENCE)
        )
    }

    private fun memoryCurator(c: Context): Finding {
        val snapshot = SoulEngineState.snapshot(c)
        val lessons = snapshot.lessons.takeLast(5)
        val notes = lessons.map { lesson ->
            "${clean(lesson.category, 40)} · outcome=${clean(lesson.outcome, 40)} · ${clean(lesson.summary, 100)}"
        }
        return Finding(
            Role.MEMORY_CURATOR,
            if (lessons.isEmpty()) "idle" else "review_only",
            "Summarized ${lessons.size} existing sanitized lesson(s); no new memory was saved.",
            if (notes.isEmpty()) listOf("No lessons are available yet.") else notes.take(MAX_EVIDENCE)
        )
    }

    private fun encode(report: RunReport): JSONObject = JSONObject()
        .put("id", report.id).put("at", report.at).put("summary", clean(report.summary, 700))
        .put("findings", JSONArray().also { array ->
            report.findings.forEach { f ->
                array.put(JSONObject().put("role", f.role.name).put("status", clean(f.status, 60))
                    .put("summary", clean(f.summary, 300)).put("evidence", JSONArray(f.evidence.map { clean(it) }.take(MAX_EVIDENCE))))
            }
        })

    fun run(context: Context, force: Boolean = false): RunReport = synchronized(lock) {
        val c = context.applicationContext
        val old = readRuns(c)
        val lastAt = old.optJSONObject(old.length() - 1)?.optLong("at") ?: 0L
        val now = System.currentTimeMillis()
        if (!force && lastAt > 0L && now - lastAt < COOLDOWN_MS) {
            return@synchronized RunReport("cooldown", now, emptyList(), "Subagent cooldown is active; no new run was started.")
        }

        // Sequential execution makes the budget deterministic and prevents subagents competing for state.
        val findings = listOf(
            planner(c),
            capabilityAuditor(c),
            safetyReviewer(c),
            memoryCurator(c)
        )
        val id = "subagents-$now"
        val summary = "Coordinated ${findings.size} bounded local specialists. No external model calls, tools, permissions, or consequential actions were invoked."
        val report = RunReport(id, now, findings, summary)
        val updated = JSONArray()
        val first = (old.length() - MAX_RUNS + 1).coerceAtLeast(0)
        for (i in first until old.length()) old.optJSONObject(i)?.let(updated::put)
        updated.put(encode(report))
        val saved = prefs(c).edit().putString(KEY, updated.toString()).commit()
        if (!saved) report.copy(summary = "Subagents completed, but the report could not be saved durably; no actions were taken.")
        else report
    }

    fun latestReport(context: Context): String = synchronized(lock) {
        val array = readRuns(context.applicationContext)
        if (array.length() == 0) return@synchronized "No subagent run is saved yet. Use “run subagents”."
        val o = array.optJSONObject(array.length() - 1) ?: return@synchronized "Saved subagent report is unreadable."
        val findings = o.optJSONArray("findings") ?: JSONArray()
        val lines = (0 until findings.length()).mapNotNull { i ->
            val f = findings.optJSONObject(i) ?: return@mapNotNull null
            val ev = f.optJSONArray("evidence") ?: JSONArray()
            val evidence = (0 until ev.length()).joinToString("\n") { "  • " + clean(ev.optString(it), 180) }
            "${f.optString("role")} [${f.optString("status")}]: ${clean(f.optString("summary"), 260)}" +
                if (evidence.isBlank()) "" else "\n" + evidence
        }
        clean(o.optString("summary"), 600) + if (lines.isEmpty()) "" else "\n\n" + lines.joinToString("\n\n")
    }

    fun clearForTest(context: Context) {
        synchronized(lock) { prefs(context.applicationContext).edit().clear().commit() }
    }
}
