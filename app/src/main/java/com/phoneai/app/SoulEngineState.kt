package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Durable, evidence-led companion state. Stores sanitized labels and tool names only:
 * never raw tool arguments, screen text, provider output, credentials, OTPs, or message bodies.
 * A candidate is a proposal, not an executable route. Dispatch still uses ToolRegistry and AgentPolicy.
 */
internal object SoulEngineState {
    enum class Mode { TALK, COMMAND }
    enum class ProposalStatus { PENDING, ACCEPTED, EDITED, REJECTED, STALE }
    enum class ProposalKind { ROUTE, SKILL_NOTE, PROMPT_TIP, TOOL_GUIDANCE }

    data class Proposal(
        val id: String, val kind: ProposalKind, val title: String, val detail: String,
        val allowedTools: List<String>, val status: ProposalStatus, val evidenceCount: Int,
        val createdAt: Long, val updatedAt: Long
    )
    data class Lesson(val id: String, val category: String, val summary: String, val outcome: String, val createdAt: Long)
    data class Snapshot(
        val goal: String, val mode: Mode, val watching: List<String>, val proposals: List<Proposal>,
        val lessons: List<Lesson>, val lastTick: Long, val lastOutcome: String,
        val watcherStatus: String, val watcherAction: String, val watcherAt: Long
    )

    private const val PREFS = "orion_soul_state_v1"
    private const val KEY = "state_json"
    private const val MAX_ITEMS = 40
    private const val MAX_LABEL = 180
    private val lock = Any()
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun safeText(raw: String, max: Int = MAX_LABEL): String {
        // If the label itself appears to contain a secret/code, omit the whole label instead of guessing boundaries.
        if (Regex("(?i)\\b(password|passcode|one[- ]time code|otp|recovery phrase|seed phrase|private key)\\b").containsMatchIn(raw)) {
            return "[sensitive content omitted]"
        }
        var s = AgentPrivacyFilter.safeCommand(raw).replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"), "[REDACTED]")
        s = s.replace(Regex("(?i)\\b(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password|passwd|secret|authorization|pin|otp|passcode)\\b\\s*[:=]\\s*[^\\s,;]+"), "[REDACTED]")
        s = s.replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[EMAIL]")
        s = s.replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[URL]")
        return s.take(max)
    }

    private fun read(c: Context): JSONObject = try { JSONObject(prefs(c).getString(KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    private fun write(c: Context, value: JSONObject): Boolean = prefs(c).edit().putString(KEY, value.toString()).commit()
    private fun strings(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.take(MAX_ITEMS)
    }

    private fun proposals(o: JSONObject): List<Proposal> {
        val a = o.optJSONArray("proposals") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i ->
            val p = a.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                val toolArray = p.optJSONArray("allowedTools") ?: JSONArray()
                Proposal(p.optString("id"), ProposalKind.valueOf(p.optString("kind")),
                    safeText(p.optString("title")), safeText(p.optString("detail"), 600),
                    (0 until toolArray.length()).map { toolArray.optString(it) }.filter { it.isNotBlank() }.take(12),
                    ProposalStatus.valueOf(p.optString("status")), p.optInt("evidenceCount").coerceAtLeast(0),
                    p.optLong("createdAt"), p.optLong("updatedAt"))
            }.getOrNull()
        }
    }

    private fun lessons(o: JSONObject): List<Lesson> {
        val a = o.optJSONArray("lessons") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i ->
            val l = a.optJSONObject(i) ?: return@mapNotNull null
            runCatching { Lesson(l.optString("id"), safeText(l.optString("category"), 60),
                safeText(l.optString("summary"), 300), safeText(l.optString("outcome"), 60), l.optLong("createdAt")) }.getOrNull()
        }
    }

    fun snapshot(context: Context): Snapshot = synchronized(lock) {
        val o = read(context)
        Snapshot(safeText(o.optString("goal")), runCatching { Mode.valueOf(o.optString("mode", "TALK")) }.getOrDefault(Mode.TALK),
            strings(o, "watching"), proposals(o), lessons(o), o.optLong("lastTick"), safeText(o.optString("lastOutcome"), 100),
            safeText(o.optString("watcherStatus"), 40), safeText(o.optString("watcherAction"), 100), o.optLong("watcherAt"))
    }

    fun recordWatcherStatus(context: Context, status: String, action: String): Boolean = synchronized(lock) {
        val o = read(context)
        val now = System.currentTimeMillis()
        o.put("watcherStatus", safeText(status, 40))
            .put("watcherAction", safeText(action, 100))
            .put("watcherAt", now)
            .put("lastTick", now)
            .put("lastOutcome", "watcher:$status")
        write(context, o)
    }

    fun setGoal(context: Context, goal: String, mode: Mode? = null): Boolean = synchronized(lock) {
        val o = read(context).put("goal", safeText(goal)).put("lastTick", System.currentTimeMillis())
        if (mode != null) o.put("mode", mode.name)
        write(context, o)
    }

    fun setWatching(context: Context, items: List<String>): Boolean = synchronized(lock) {
        val o = read(context)
        o.put("watching", JSONArray(items.map { safeText(it, 100) }.filter { it.isNotBlank() }.distinct().take(10)))
        o.put("lastTick", System.currentTimeMillis())
        write(context, o)
    }

    fun recordLesson(context: Context, category: String, summary: String, outcome: String): Boolean = synchronized(lock) {
        val clean = safeText(summary, 300)
        if (clean.isBlank()) return@synchronized false
        val o = read(context)
        val old = lessons(o).toMutableList()
        if (old.any { it.summary.equals(clean, true) && it.outcome == outcome }) {
            o.put("lastOutcome", safeText(outcome, 60)).put("lastTick", System.currentTimeMillis())
            return@synchronized write(context, o)
        }
        old.add(Lesson(java.util.UUID.randomUUID().toString(), safeText(category, 60), clean, safeText(outcome, 60), System.currentTimeMillis()))
        val a = JSONArray()
        old.takeLast(MAX_ITEMS).forEach { a.put(JSONObject().put("id", it.id).put("category", it.category).put("summary", it.summary).put("outcome", it.outcome).put("createdAt", it.createdAt)) }
        o.put("lessons", a).put("lastOutcome", safeText(outcome, 60)).put("lastTick", System.currentTimeMillis())
        write(context, o)
    }

    /** Add a sanitized, review-only maintenance proposal from a real watcher finding. */
    fun proposeWatchIssue(context: Context, category: String, issue: String, suggestion: String): Boolean = synchronized(lock) {
        val safeIssue = safeText(issue, 220)
        val safeSuggestion = safeText(suggestion, 360)
        if (safeIssue.isBlank() || safeIssue == "[sensitive content omitted]") return@synchronized false
        val o = read(context)
        val current = proposals(o)
        val duplicate = current.firstOrNull {
            it.status == ProposalStatus.PENDING && it.kind == ProposalKind.TOOL_GUIDANCE &&
                it.title.equals(safeIssue, ignoreCase = true)
        }
        if (duplicate != null) return@synchronized true
        val now = System.currentTimeMillis()
        val proposal = Proposal(
            id = java.util.UUID.randomUUID().toString(),
            kind = ProposalKind.TOOL_GUIDANCE,
            title = safeIssue,
            detail = safeSuggestion + " This is a proposal only; no setting, permission, source file, or tool behavior was changed.",
            allowedTools = emptyList(),
            status = ProposalStatus.PENDING,
            evidenceCount = 1,
            createdAt = now,
            updatedAt = now
        )
        o.put("proposals", JSONArray().also { a ->
            (current + proposal).takeLast(MAX_ITEMS).forEach { a.put(it.json()) }
        }).put("lastTick", now)
        write(context, o)
    }

    /** Called only after a durable completed checkpoint; candidates never execute themselves. */
    fun learnFromCompletedCheckpoint(context: Context, checkpoint: JSONObject): Boolean = synchronized(lock) {
        val steps = checkpoint.optJSONArray("steps") ?: JSONArray()
        val tools = ArrayList<String>()
        var successes = 0
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            val tool = step.optString("tool").trim()
            val result = step.optString("result")
            if (tool.isBlank() || result.startsWith("error", true)) continue
            if (tool !in setOf("remember", "update_memory", "forget", "recall", "type", "copy", "sms", "telegram_send", "mcp_call")) {
                successes++
                if (tool !in tools) tools += tool
            }
        }
        val label = safeText(checkpoint.optString("command"))
        if (checkpoint.optString("status") != "completed" || label.isBlank() || label == "[sensitive content omitted]" || successes < 2 || tools.size < 2) return@synchronized true
        if (label.contains(Regex("(?i)\\b(bank|payment|transfer money|send money|withdraw|deposit|checkout|trade order|wallet|password|passcode|pin|otp|login|sign in|credential|recovery phrase)\\b"))) return@synchronized true
        val o = read(context)
        val existing = proposals(o).firstOrNull { it.kind == ProposalKind.ROUTE && it.title.equals(label, true) && it.status == ProposalStatus.PENDING }
        if (existing != null) {
            replaceProposal(o, existing.copy(evidenceCount = existing.evidenceCount + 1, updatedAt = System.currentTimeMillis()))
        } else {
            val candidate = Proposal(java.util.UUID.randomUUID().toString(), ProposalKind.ROUTE, label,
                "Observed a completed multi-step task. Candidate tool sequence: " + tools.take(8).joinToString(" → ") +
                    ". Planning guidance only; each future action must be revalidated and pass the live policy gate.",
                tools.take(8), ProposalStatus.PENDING, 1, System.currentTimeMillis(), System.currentTimeMillis())
            val all = proposals(o).toMutableList().apply { add(candidate) }
            o.put("proposals", JSONArray().also { a -> all.takeLast(MAX_ITEMS).forEach { a.put(it.json()) } })
        }
        o.put("lastOutcome", "verified multi-step task").put("lastTick", System.currentTimeMillis())
        write(context, o)
    }

    private fun Proposal.json() = JSONObject().put("id", id).put("kind", kind.name).put("title", title)
        .put("detail", detail).put("allowedTools", JSONArray(allowedTools)).put("status", status.name)
        .put("evidenceCount", evidenceCount).put("createdAt", createdAt).put("updatedAt", updatedAt)

    private fun replaceProposal(o: JSONObject, proposal: Proposal) {
        val all = proposals(o).map { if (it.id == proposal.id) proposal else it }
        o.put("proposals", JSONArray().also { a -> all.takeLast(MAX_ITEMS).forEach { a.put(it.json()) } })
    }

    /** Review is not an execution grant; accepted tool hints are intersected with the live registry. */
    fun reviewProposal(context: Context, id: String, status: ProposalStatus, editedTitle: String? = null): Boolean = synchronized(lock) {
        if (status !in setOf(ProposalStatus.ACCEPTED, ProposalStatus.EDITED, ProposalStatus.REJECTED)) return@synchronized false
        val o = read(context)
        val p = proposals(o).firstOrNull { it.id == id && it.status == ProposalStatus.PENDING } ?: return@synchronized false
        val validTools = p.allowedTools.filter { it in ToolRegistry.names() }
        if (status != ProposalStatus.REJECTED && p.kind == ProposalKind.ROUTE && validTools.isEmpty()) return@synchronized false
        val updated = p.copy(title = editedTitle?.let { safeText(it) }?.takeIf { it.isNotBlank() } ?: p.title,
            allowedTools = validTools, status = status, updatedAt = System.currentTimeMillis())
        replaceProposal(o, updated)
        o.put("lastTick", System.currentTimeMillis())
        write(context, o)
    }

    /** Retrieve relevant sanitized lessons and accepted route hints; these are never execution authority. */
    fun relatedContext(context: Context, request: String, limit: Int = 3): List<String> = synchronized(lock) {
        val query = safeText(request, 500).lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.toSet()
        if (query.isEmpty()) return@synchronized emptyList()
        val s = snapshot(context)
        val entries = buildList {
            if (s.goal.isNotBlank()) add("current goal: " + s.goal)
            s.proposals.filter { it.status == ProposalStatus.ACCEPTED || it.status == ProposalStatus.EDITED }
                .forEach { add("accepted ${it.kind.name.lowercase()} hint: ${it.title}; tools=${it.allowedTools.joinToString()}") }
            s.lessons.forEach { add("lesson (${it.outcome}): ${it.summary}") }
        }
        entries.map { entry ->
            val words = entry.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.toSet()
            entry to (query intersect words).size
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit.coerceIn(1, 5)).map { it.first }
    }

    fun proposalPrompt(context: Context): String = synchronized(lock) {
        val pending = proposals(read(context)).filter { it.status == ProposalStatus.PENDING }.take(5)
        if (pending.isEmpty()) "No learning proposals are waiting for review."
        else pending.joinToString("\n") { "- ${it.kind.name.lowercase()}: ${it.title} (evidence=${it.evidenceCount}) — ${it.detail}" }
    }
}
