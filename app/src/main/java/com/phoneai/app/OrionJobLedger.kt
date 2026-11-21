package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The job runner's spine. One objective, split into steps; a step only counts once it carries evidence (a file, an output, a page).
 * The open job is shown to the model every turn, so work continues until every step is verified or honestly blocked,
 * and it survives process death. Pure parse/format core so the rules are testable.
 */
internal object OrionJobLedger {
    enum class State { OPEN, VERIFIED, BLOCKED }
    data class Step(val text: String, val state: State = State.OPEN, val evidence: String = "")
    data class Job(val objective: String, val steps: List<Step>, val startedAt: Long) {
        val open: List<Step> get() = steps.filter { it.state == State.OPEN }
        val finished: Boolean get() = steps.isNotEmpty() && open.isEmpty()
    }

    private const val PREFS = "orion_job"

    fun start(objective: String, steps: List<String>, now: Long = System.currentTimeMillis()): Job =
        Job(objective.trim().take(300), steps.map { it.trim().take(200) }.filter { it.isNotBlank() }.take(12).map { Step(it) }, now)

    /** A step is verified only with evidence; "done" without proof is refused. */
    fun mark(job: Job, index: Int, state: State, evidence: String): Pair<Job, String> {
        if (index !in job.steps.indices) return job to "error: no step $index (1-${job.steps.size})."
        if (state == State.VERIFIED && evidence.trim().length < 8) return job to "error: step ${index + 1} not marked verified; give the evidence (file path, output line, page read)."
        if (state == State.BLOCKED && evidence.trim().length < 8) return job to "error: say exactly what blocks step ${index + 1}."
        val steps = job.steps.toMutableList(); steps[index] = steps[index].copy(state = state, evidence = evidence.trim().take(300))
        return job.copy(steps = steps) to "ok"
    }

    fun format(job: Job?): String {
        if (job == null) return "No job open."
        val sb = StringBuilder("JOB: ${job.objective}\n")
        job.steps.forEachIndexed { i, s -> sb.append("${i + 1}. [${s.state.name}] ${s.text}").append(if (s.evidence.isNotBlank()) " — ${s.evidence}" else "").append('\n') }
        sb.append(if (job.finished) "All steps resolved: review the result against the objective, then report." else "${job.open.size} step(s) still open: keep working; do not report as done.")
        return sb.toString()
    }

    fun toJson(job: Job): String = JSONObject().put("objective", job.objective).put("startedAt", job.startedAt)
        .put("steps", JSONArray(job.steps.map { JSONObject().put("t", it.text).put("s", it.state.name).put("e", it.evidence) })).toString()

    fun fromJson(json: String?): Job? = runCatching {
        val o = JSONObject(json ?: return null); val a = o.getJSONArray("steps")
        Job(o.getString("objective"), (0 until a.length()).map { val s = a.getJSONObject(it); Step(s.getString("t"), State.valueOf(s.getString("s")), s.optString("e")) }, o.optLong("startedAt"))
    }.getOrNull()

    fun current(c: Context): Job? = fromJson(c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("job", null))
    fun save(c: Context, job: Job?) {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { if (job == null) remove("job") else putString("job", toJson(job)) }.apply()
    }

    /** An unfinished job is never silently dropped: queue one resume follow-up (once) so the proactive loop carries it on without the owner. */
    fun resumeNote(c: Context): String {
        val job = current(c) ?: return ""
        if (job.finished) return ""
        val tag = "Resume job: " + job.objective.take(60)
        val have = runCatching { OrionFollowUpStore.pending(c).any { it.text.startsWith(tag) } }.getOrDefault(true)
        if (!have) runCatching { OrionFollowUpStore.add(c, tag + ". Next open step: " + job.open.first().text + ". Continue it, verify with evidence, mark it, then report.") }
        return "\n\nJob still open: ${job.open.size} of ${job.steps.size} steps unverified. I will keep going on it and report when it is finished."
    }

    /** brain {action:"job", op: start|step|status|close, objective, steps[], index, state, evidence} */
    fun run(c: Context, a: JSONObject): String = when (a.optString("op").ifBlank { "status" }) {
        "start" -> {
            val steps = a.optJSONArray("steps")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
            val job = start(a.optString("objective"), steps)
            if (job.objective.isBlank() || job.steps.size < 2) "error: a job needs an objective and at least 2 steps." else { save(c, job); format(job) }
        }
        "step" -> current(c)?.let { job ->
            val st = runCatching { State.valueOf(a.optString("state", "verified").uppercase()) }.getOrDefault(State.VERIFIED)
            val (next, msg) = mark(job, a.optInt("index") - 1, st, a.optString("evidence"))
            if (msg == "ok") { save(c, next); format(next) } else msg
        } ?: "No job open."
        "close" -> { save(c, null); "Job closed." }
        else -> format(current(c))
    }
}
