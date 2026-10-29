package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Stage 5: local Propose–Probe–Compose loop.
 *
 * Probes are dry-run structural comparisons only: no candidate tool is executed and no task
 * outcome is fabricated. The baseline has no learned route hints; the candidate is checked
 * against the current live tool registry. Composition produces a review-only planning note.
 * Nothing here grants permission, edits source, enables a skill, or bypasses AgentPolicy.
 */
internal object SoulP3Engine {
    data class ProbeRecord(
        val proposalId: String,
        val title: String,
        val baselineHints: Int,
        val candidateTools: Int,
        val availableTools: Int,
        val missingTools: List<String>,
        val evidenceCount: Int,
        val verdict: String,
        val at: Long
    )

    data class Composition(
        val proposalId: String,
        val title: String,
        val guidance: String,
        val evidenceCount: Int,
        val at: Long
    )

    data class RunResult(val probesRun: Int, val compositionsCreated: Int, val summary: String)

    private const val PREFS = "orion_soul_p3_v1"
    private const val KEY_PROBES = "probes"
    private const val KEY_COMPOSITIONS = "compositions"
    private const val MAX_RECORDS = 20
    private const val MIN_EVIDENCE = 2
    private val lock = Any()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readArray(c: Context, key: String): JSONArray = try {
        JSONArray(prefs(c).getString(key, "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    private fun writeArray(c: Context, key: String, array: JSONArray): Boolean =
        prefs(c).edit().putString(key, array.toString()).commit()

    private fun safe(value: String, limit: Int = 180): String {
        val cleaned = AgentPrivacyFilter.safeCommand(value)
            .replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)\\b(password|passcode|otp|seed phrase|private key|api[_ -]?key|access token)\\b[^,;]*"), "[sensitive content omitted]")
        return cleaned.take(limit)
    }

    private fun probes(c: Context): List<ProbeRecord> {
        val a = readArray(c, KEY_PROBES)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                val missing = o.optJSONArray("missing") ?: JSONArray()
                ProbeRecord(
                    o.optString("id"), safe(o.optString("title")),
                    o.optInt("baselineHints"), o.optInt("candidateTools"),
                    o.optInt("availableTools"), (0 until missing.length()).map { safe(missing.optString(it), 80) },
                    o.optInt("evidence"), safe(o.optString("verdict"), 100), o.optLong("at")
                )
            }.getOrNull()
        }
    }

    private fun compositions(c: Context): List<Composition> {
        val a = readArray(c, KEY_COMPOSITIONS)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                Composition(o.optString("id"), safe(o.optString("title")),
                    safe(o.optString("guidance"), 700), o.optInt("evidence"), o.optLong("at"))
            }.getOrNull()
        }
    }

    fun run(context: Context): RunResult = synchronized(lock) {
        val c = context.applicationContext
        val state = SoulEngineState.snapshot(c)
        val pending = state.proposals.filter {
            it.status == SoulEngineState.ProposalStatus.PENDING &&
                it.kind == SoulEngineState.ProposalKind.ROUTE
        }.take(3)
        if (pending.isEmpty()) {
            return@synchronized RunResult(0, 0, "No pending route candidates to probe; baseline remains unchanged.")
        }

        val registered = ToolRegistry.names().toSet()
        val oldProbes = probes(c).toMutableList()
        val oldCompositions = compositions(c).toMutableList()
        var composed = 0
        val now = System.currentTimeMillis()

        pending.forEach { candidate ->
            val hinted = candidate.allowedTools.distinct()
            val available = hinted.filter { it in registered }
            val missing = hinted.filterNot { it in registered }
            val verdict = when {
                hinted.isEmpty() -> "blocked_no_tool_hints"
                missing.isNotEmpty() -> "blocked_missing_tools"
                candidate.evidenceCount < MIN_EVIDENCE -> "insufficient_evidence"
                else -> "candidate_ready_for_review"
            }
            // Baseline = no learned route guidance. Candidate score is only current registry
            // coverage, not task success; actual execution outcomes are not inferred here.
            oldProbes.add(ProbeRecord(
                candidate.id, safe(candidate.title), 0, hinted.size, available.size,
                missing.map { safe(it, 80) }, candidate.evidenceCount, verdict, now
            ))

            if (verdict == "candidate_ready_for_review" &&
                oldCompositions.none { it.proposalId == candidate.id }) {
                val title = safe(candidate.title)
                val toolNames = available.joinToString(" → ")
                val guidance = "Draft planning guidance for \"$title\": consider the previously observed tool sequence ($toolNames) as an optional candidate only. Re-check current capability availability, validate every argument, inspect live screen/context, and pass every action through the normal policy/approval gate. If any step is unavailable, ambiguous, sensitive, or consequential, stop and ask the user. This draft is not executable and does not prove better task success."
                oldCompositions.add(Composition(candidate.id, title, guidance, candidate.evidenceCount, now))
                composed++
            }
        }

        val savedProbes = oldProbes.takeLast(MAX_RECORDS)
        val savedCompositions = oldCompositions.takeLast(MAX_RECORDS)
        val probeJson = JSONArray().also { a -> savedProbes.forEach { p ->
            a.put(JSONObject().put("id", p.proposalId).put("title", p.title)
                .put("baselineHints", p.baselineHints).put("candidateTools", p.candidateTools)
                .put("availableTools", p.availableTools).put("missing", JSONArray(p.missingTools))
                .put("evidence", p.evidenceCount).put("verdict", p.verdict).put("at", p.at))
        } }
        val compositionJson = JSONArray().also { a -> savedCompositions.forEach { p ->
            a.put(JSONObject().put("id", p.proposalId).put("title", p.title)
                .put("guidance", p.guidance).put("evidence", p.evidenceCount).put("at", p.at))
        } }
        val probesSaved = writeArray(c, KEY_PROBES, probeJson)
        val compositionsSaved = writeArray(c, KEY_COMPOSITIONS, compositionJson)
        val summary = when {
            !probesSaved || !compositionsSaved -> "Probe ran but its report could not be durably saved; no adoption is claimed."
            else -> "Probed ${pending.size} candidate(s) against the current tool registry; composed ${composed} review-only guidance draft(s). No tools were executed and no candidate was auto-adopted."
        }
        RunResult(pending.size, if (probesSaved && compositionsSaved) composed else 0, summary)
    }

    fun probeReport(context: Context): String = synchronized(lock) {
        val records = probes(context.applicationContext)
        if (records.isEmpty()) return@synchronized "No probe results yet. Run “run probes” from the Soul Engine Watcher."
        records.takeLast(8).asReversed().joinToString("\\n") { p ->
            "- ${p.title}: ${p.verdict}; candidate tool coverage ${p.availableTools}/${p.candidateTools}; baseline learned hints ${p.baselineHints}; evidence ${p.evidenceCount}; missing ${p.missingTools.joinToString().ifBlank { "none" }}"
        } + "\\nCoverage is structural only; it is not a measured task-success score."
    }

    fun compositionReport(context: Context): String = synchronized(lock) {
        val drafts = compositions(context.applicationContext)
        if (drafts.isEmpty()) return@synchronized "No composition drafts yet. A candidate needs at least $MIN_EVIDENCE completed-task evidence records and all hinted tools must still exist."
        drafts.takeLast(5).asReversed().joinToString("\\n\\n") { p ->
            "DRAFT — ${p.title} (evidence=${p.evidenceCount})\\n${p.guidance}"
        } + "\\n\\nDrafts are review-only; none is injected into execution automatically."
    }

    fun clearReportsForTest(context: Context) {
        synchronized(lock) {
            prefs(context.applicationContext).edit().clear().commit()
        }
    }
}
