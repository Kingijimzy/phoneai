package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A user-authored goal Orion may run by itself on a schedule.
 *
 * This is the "alive" half of the assistant: instead of waiting for a message, Orion can
 * pick up a standing goal, run it through the normal agent loop (with every gate intact),
 * and report the outcome. It is opt-in and bounded on purpose: autonomous background work
 * spends model quota and battery, and must never surprise the user with an action.
 */
data class AutonomyGoal(
    val id: String,
    val prompt: String,
    val everyMinutes: Int,
    val lastRunAt: Long,
    val lastResult: String,
    val enabled: Boolean
)

internal object AutonomyGoalPolicy {
    const val MIN_MINUTES = 30
    const val MAX_MINUTES = 7 * 24 * 60
    const val MAX_GOALS = 5

    fun validate(prompt: String, minutes: Int): String? = when {
        prompt.trim().length < 8 -> "the goal needs a concrete instruction (at least 8 characters)"
        prompt.trim().length > 500 -> "the goal is too long (max 500 characters)"
        minutes < MIN_MINUTES -> "minimum interval is $MIN_MINUTES minutes (saves battery and model quota)"
        minutes > MAX_MINUTES -> "maximum interval is one week"
        else -> null
    }

    /** Due once enabled and the interval has elapsed; a never-run goal is due at the next tick. */
    fun due(goal: AutonomyGoal, now: Long): Boolean =
        goal.enabled && (goal.lastRunAt <= 0L || now - goal.lastRunAt >= goal.everyMinutes * 60_000L)

    fun describe(goal: AutonomyGoal): String =
        "${goal.id} [${if (goal.enabled) "ON" else "OFF"}] every ${goal.everyMinutes} min: ${goal.prompt.take(160)}"
}

object AutonomyGoalStore {
    private const val FILE = "orion_autonomy_goals.json"
    private val lock = Any()
    private fun file(c: Context) = File(c.filesDir, FILE)

    fun all(c: Context): List<AutonomyGoal> = synchronized(lock) {
        try {
            val f = file(c)
            if (!f.exists()) return@synchronized emptyList()
            val a = JSONArray(f.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                AutonomyGoal(
                    o.optString("id", "g$i"),
                    o.optString("prompt"),
                    o.optInt("every", 120),
                    o.optLong("last", 0L),
                    o.optString("result"),
                    o.optBoolean("enabled", true)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(c: Context, list: List<AutonomyGoal>) {
        try {
            val a = JSONArray()
            list.forEach { g ->
                a.put(JSONObject()
                    .put("id", g.id)
                    .put("prompt", g.prompt)
                    .put("every", g.everyMinutes)
                    .put("last", g.lastRunAt)
                    .put("result", g.lastResult)
                    .put("enabled", g.enabled))
            }
            file(c).writeText(a.toString())
        } catch (_: Exception) { }
    }

    fun add(c: Context, prompt: String, minutes: Int): String = synchronized(lock) {
        val clean = prompt.trim()
        AutonomyGoalPolicy.validate(clean, minutes)?.let { return@synchronized "error: $it" }
        val list = all(c)
        if (list.size >= AutonomyGoalPolicy.MAX_GOALS) {
            return@synchronized "error: at most ${AutonomyGoalPolicy.MAX_GOALS} scheduled goals"
        }
        val nextId = "g" + ((list.mapNotNull { it.id.removePrefix("g").toIntOrNull() }.maxOrNull() ?: 0) + 1)
        save(c, list + AutonomyGoal(nextId, clean, minutes, 0L, "", true))
        "Scheduled $nextId: I'll run \"$clean\" about every $minutes minutes while Orion is running, and stop if the phone is busy. Say \"list goals\" to review."
    }

    fun remove(c: Context, id: String): String = synchronized(lock) {
        val list = all(c)
        if (list.none { it.id == id }) return@synchronized "error: no scheduled goal '$id'"
        save(c, list.filterNot { it.id == id })
        "Removed scheduled goal '$id'."
    }

    fun setEnabled(c: Context, id: String, enabled: Boolean): String = synchronized(lock) {
        val list = all(c)
        if (list.none { it.id == id }) return@synchronized "error: no scheduled goal '$id'"
        save(c, list.map { if (it.id == id) it.copy(enabled = enabled) else it })
        "${if (enabled) "Enabled" else "Paused"} scheduled goal '$id'."
    }

    fun due(c: Context, now: Long): List<AutonomyGoal> =
        all(c).filter { AutonomyGoalPolicy.due(it, now) }

    /** Claim a goal before running it so the next tick cannot start the same work twice. */
    fun markRan(c: Context, id: String, result: String) = synchronized(lock) {
        save(c, all(c).map { g ->
            if (g.id == id) g.copy(lastRunAt = System.currentTimeMillis(), lastResult = result.take(300)) else g
        })
    }

    fun describe(c: Context): String {
        val list = all(c)
        if (list.isEmpty()) return "no scheduled goals yet"
        return list.joinToString("\n") { AutonomyGoalPolicy.describe(it) }
    }
}
