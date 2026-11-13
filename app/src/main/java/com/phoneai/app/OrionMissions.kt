package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orion's standing work: one durable ledger of missions it owns until they are done or honestly blocked.
 *
 * Missions come from the owner's follow-ups, Orion's own self-repair and capability checks, and its initiative.
 * The ledger survives process death: a mission left RUNNING is reopened, every attempt is counted, failed attempts
 * back off, and a mission that keeps failing is marked BLOCKED so Orion tells Kingi instead of retrying forever.
 * It only schedules and records; the Agent still does the work through the same tools and approvals.
 */
internal object OrionMissions {
    enum class State { OPEN, RUNNING, DONE, BLOCKED }
    enum class Outcome { COMPLETE, BLOCKED, UNKNOWN }

    data class Mission(
        val id: String,
        val title: String,
        val source: String,
        val priority: Int,
        val state: State,
        val attempts: Int,
        val nextAt: Long,
        val startedAt: Long,
        val note: String,
        val createdAt: Long
    )

    const val MAX_ATTEMPTS = 4
    const val STALE_RUNNING_MS = 30 * 60_000L
    private const val MAX_MISSIONS = 40
    private const val PREFS = "orion_missions_v1"
    private const val KEY = "items"
    private val lock = Any()

    // ---- pure rules (unit tested) ----

    fun backoffMs(attempts: Int): Long = when {
        attempts <= 1 -> 10 * 60_000L
        attempts == 2 -> 60 * 60_000L
        else -> 6 * 60 * 60_000L
    }

    fun normalize(title: String): String = title.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim().take(120)

    /** A mission left RUNNING by a process that died is put back in the queue. */
    fun reconcile(list: List<Mission>, now: Long): List<Mission> = list.map {
        if (it.state == State.RUNNING && now - it.startedAt > STALE_RUNNING_MS) it.copy(state = State.OPEN, nextAt = now, note = "resumed after interruption") else it
    }

    /** Highest priority first, then oldest. Only OPEN missions that are due. */
    fun pickNext(list: List<Mission>, now: Long): Mission? =
        list.filter { it.state == State.OPEN && it.nextAt <= now }
            .sortedWith(compareByDescending<Mission> { it.priority }.thenBy { it.createdAt }).firstOrNull()

    fun outcomeOf(reply: String): Outcome {
        val t = reply.uppercase()
        return when {
            t.contains("MISSION BLOCKED") -> Outcome.BLOCKED
            t.contains("MISSION COMPLETE") -> Outcome.COMPLETE
            else -> Outcome.UNKNOWN
        }
    }

    fun afterAttempt(m: Mission, outcome: Outcome, note: String, now: Long): Mission = when (outcome) {
        Outcome.COMPLETE -> m.copy(state = State.DONE, note = note.take(200), nextAt = 0L)
        Outcome.BLOCKED -> m.copy(state = State.BLOCKED, note = note.take(200), nextAt = 0L)
        Outcome.UNKNOWN ->
            if (m.attempts >= MAX_ATTEMPTS) m.copy(state = State.BLOCKED, note = "no verified result after ${m.attempts} attempts", nextAt = 0L)
            else m.copy(state = State.OPEN, note = note.take(200).ifBlank { "attempt ${m.attempts} gave no verified result" }, nextAt = now + backoffMs(m.attempts))
    }

    fun prompt(m: Mission): String =
        "[ORION MISSION — yours until finished; Kingi did not just ask for this] Mission: ${m.title}\n" +
            "Source: ${m.source}. Attempt ${m.attempts} of $MAX_ATTEMPTS.${if (m.note.isNotBlank()) " Last note: ${m.note}." else ""}\n" +
            "Understand the goal, inspect real state with tools, act, then verify the result with evidence. Build a missing tool if needed. " +
            "End your reply with exactly one line: 'MISSION COMPLETE: <what changed, verified by what>' or 'MISSION BLOCKED: <what you need>'. " +
            "Never write MISSION COMPLETE without verification."

    // ---- storage ----

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun toJson(m: Mission) = JSONObject().put("id", m.id).put("title", m.title).put("source", m.source).put("priority", m.priority)
        .put("state", m.state.name).put("attempts", m.attempts).put("nextAt", m.nextAt).put("startedAt", m.startedAt)
        .put("note", m.note).put("createdAt", m.createdAt)

    private fun load(c: Context): List<Mission> = runCatching {
        val a = JSONArray(prefs(c).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Mission(o.getString("id"), o.getString("title"), o.optString("source"), o.optInt("priority", 5),
                runCatching { State.valueOf(o.optString("state")) }.getOrDefault(State.OPEN), o.optInt("attempts"),
                o.optLong("nextAt"), o.optLong("startedAt"), o.optString("note"), o.optLong("createdAt"))
        }
    }.getOrDefault(emptyList())

    private fun save(c: Context, list: List<Mission>) {
        val kept = list.sortedBy { it.createdAt }.let { all ->
            if (all.size <= MAX_MISSIONS) all else (all.filter { it.state == State.OPEN || it.state == State.RUNNING || it.state == State.BLOCKED } +
                all.filter { it.state == State.DONE }.takeLast(10)).sortedBy { it.createdAt }.takeLast(MAX_MISSIONS)
        }
        prefs(c).edit().putString(KEY, JSONArray().also { a -> kept.forEach { a.put(toJson(it)) } }.toString()).apply()
    }

    fun all(c: Context): List<Mission> = synchronized(lock) { load(c) }

    /** Adds a mission unless an unfinished one with the same title exists. Returns its id, or "" when skipped. */
    fun add(c: Context, title: String, source: String, priority: Int = 5, now: Long = System.currentTimeMillis()): String = synchronized(lock) {
        val clean = title.trim().replace(Regex("\\s+"), " ").take(220)
        if (clean.length < 6) return@synchronized ""
        val list = load(c)
        val key = normalize(clean)
        if (list.any { normalize(it.title) == key && it.state != State.DONE }) return@synchronized ""
        val id = java.util.UUID.randomUUID().toString().take(12)
        save(c, list + Mission(id, clean, source, priority.coerceIn(1, 9), State.OPEN, 0, now, 0L, "", now))
        id
    }

    /** Turns due follow-ups into missions so nothing Orion or Kingi noted is left sitting. */
    fun importFollowUps(c: Context, now: Long = System.currentTimeMillis()): Int = runCatching {
        var n = 0
        OrionFollowUpStore.overdue(c, now).take(5).forEach { f ->
            val repair = f.text.startsWith("Self-repair", true)
            if (add(c, f.text, if (repair) "self-repair" else "follow-up", if (repair) 7 else 5, now).isNotBlank()) n++
            OrionFollowUpStore.done(c, f.id)
        }
        n
    }.getOrDefault(0)

    /** Reopens interrupted work and claims the next due mission (RUNNING, attempt counted). */
    fun claimNext(c: Context, now: Long = System.currentTimeMillis()): Mission? = synchronized(lock) {
        val list = reconcile(load(c), now)
        val next = pickNext(list, now)
        if (next == null) { save(c, list); return@synchronized null }
        val claimed = next.copy(state = State.RUNNING, attempts = next.attempts + 1, startedAt = now)
        save(c, list.map { if (it.id == next.id) claimed else it })
        claimed
    }

    fun finish(c: Context, id: String, reply: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val list = load(c)
        val m = list.firstOrNull { it.id == id } ?: return@synchronized
        val outcome = outcomeOf(reply)
        val line = reply.lineSequence().map { it.trim() }.lastOrNull { it.uppercase().startsWith("MISSION") } ?: reply.trim().takeLast(160)
        val updated = afterAttempt(m, outcome, line, now)
        save(c, list.map { if (it.id == id) updated else it })
        if (updated.state == State.DONE) runCatching { OrionDrives.onMissionDone(c); OrionLessons.learn(c, "Finished mission: ${m.title.take(100)}. ${updated.note.take(80)}", now) }
    }

    /** What Orion currently owes: shown in every prompt so it knows its own standing work. */
    fun promptBlock(c: Context, now: Long = System.currentTimeMillis()): String {
        val list = all(c)
        val active = list.filter { it.state == State.OPEN || it.state == State.RUNNING }
        val blocked = list.filter { it.state == State.BLOCKED }.takeLast(3)
        if (active.isEmpty() && blocked.isEmpty()) return ""
        return buildString {
            append("YOUR MISSIONS (standing work you own until done; you work through these on your own):")
            active.sortedByDescending { it.priority }.take(5).forEach { append("\n- [${it.state.name.lowercase()}] ${it.title.take(120)} (attempt ${it.attempts}/$MAX_ATTEMPTS${if (it.note.isNotBlank()) ", ${it.note.take(60)}" else ""})") }
            blocked.forEach { append("\n- [blocked] ${it.title.take(100)}: ${it.note.take(80)} — tell Kingi what you need") }
        }
    }
}
