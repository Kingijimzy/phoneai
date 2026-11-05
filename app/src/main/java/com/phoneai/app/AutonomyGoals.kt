package com.phoneai.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** A user-authored goal Orion may run by itself while the owner has AUTOPILOT and Proactive enabled. */
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

    fun due(goal: AutonomyGoal, now: Long): Boolean =
        goal.enabled && now >= goal.lastRunAt &&
            (goal.lastRunAt <= 0L || now - goal.lastRunAt >= goal.everyMinutes * 60_000L)

    fun describe(goal: AutonomyGoal): String =
        "${goal.id} [${if (goal.enabled) "ON" else "OFF"}] every ${goal.everyMinutes} min: ${goal.prompt.take(160)}; last=${goal.lastResult.take(100).ifBlank { "never" }}"
}

object AutonomyGoalStore {
    private const val FILE = "orion_autonomy_goals.json"
    private val lock = Any()
    private fun atomic(c: Context) = AtomicFile(File(c.filesDir, FILE))

    fun all(c: Context): List<AutonomyGoal> = synchronized(lock) {
        try {
            val f = atomic(c)
            val json = f.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val a = JSONArray(json)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                AutonomyGoal(
                    o.optString("id", "g$i"), o.optString("prompt"),
                    o.optInt("every", 120), o.optLong("last", 0L),
                    o.optString("result"), o.optBoolean("enabled", true)
                )
            }.filter { it.prompt.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(c: Context, list: List<AutonomyGoal>): Boolean {
        val f = atomic(c)
        var stream: FileOutputStream? = null
        return try {
            val a = JSONArray()
            list.forEach { g ->
                a.put(JSONObject()
                    .put("id", g.id).put("prompt", g.prompt).put("every", g.everyMinutes)
                    .put("last", g.lastRunAt).put("result", g.lastResult.take(300))
                    .put("enabled", g.enabled))
            }
            val output = f.startWrite()
            stream = output
            output.write(a.toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(output)
            true
        } catch (_: Exception) {
            stream?.let { output -> runCatching { f.failWrite(output) } }
            false
        }
    }

    fun add(c: Context, prompt: String, minutes: Int): String = synchronized(lock) {
        val clean = prompt.trim()
        AutonomyGoalPolicy.validate(clean, minutes)?.let { return@synchronized "error: $it" }
        val list = all(c)
        if (list.size >= AutonomyGoalPolicy.MAX_GOALS) {
            return@synchronized "error: at most ${AutonomyGoalPolicy.MAX_GOALS} scheduled goals"
        }
        val nextId = "g" + ((list.mapNotNull { it.id.removePrefix("g").toIntOrNull() }.maxOrNull() ?: 0) + 1)
        if (!save(c, list + AutonomyGoal(nextId, clean, minutes, 0L, "", true))) {
            return@synchronized "error: scheduled goal could not be saved; storage is unavailable"
        }
        "Scheduled $nextId: I'll run it about every $minutes minutes only while Orion Core, Proactive and AUTOPILOT are enabled, and skip it while you are mid-task."
    }

    fun remove(c: Context, id: String): String = synchronized(lock) {
        val list = all(c)
        if (list.none { it.id == id }) return@synchronized "error: no scheduled goal '$id'"
        if (!save(c, list.filterNot { it.id == id })) "error: scheduled goal could not be removed; storage is unavailable"
        else "Removed scheduled goal '$id'."
    }

    fun setEnabled(c: Context, id: String, enabled: Boolean): String = synchronized(lock) {
        val list = all(c)
        if (list.none { it.id == id }) return@synchronized "error: no scheduled goal '$id'"
        if (!save(c, list.map { if (it.id == id) it.copy(enabled = enabled) else it })) {
            "error: scheduled goal could not be updated; storage is unavailable"
        } else "${if (enabled) "Enabled" else "Paused"} scheduled goal '$id'."
    }

    fun due(c: Context, now: Long): List<AutonomyGoal> = all(c).filter { AutonomyGoalPolicy.due(it, now) }

    /** Atomically claim one due goal before execution; duplicate ticks cannot launch it twice. */
    fun claimNextDue(c: Context, now: Long): AutonomyGoal? = synchronized(lock) {
        val list = all(c)
        val due = list.firstOrNull { AutonomyGoalPolicy.due(it, now) } ?: return@synchronized null
        val claimed = due.copy(lastRunAt = now, lastResult = "started")
        if (!save(c, list.map { if (it.id == due.id) claimed else it })) null else claimed
    }

    /** Store the outcome without moving the due-time window forward again. */
    fun recordResult(c: Context, id: String, result: String): Boolean = synchronized(lock) {
        val list = all(c)
        if (list.none { it.id == id }) return@synchronized false
        save(c, list.map { if (it.id == id) it.copy(lastResult = result.take(300)) else it })
    }

    fun describe(c: Context): String {
        val list = all(c)
        if (list.isEmpty()) return "no scheduled goals yet"
        return list.joinToString("\n") { AutonomyGoalPolicy.describe(it) }
    }
}
