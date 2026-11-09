package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/**
 * Ultron-style sticky mission: one user goal owns the session until done/cancel/new goal.
 * Background soul-watch / trading must not replace this as the answer to "continue".
 */
internal object OrionMissionLock {
    private const val PREFS = "orion_mission_lock_v1"
    private const val KEY = "active"

    data class Mission(
        val goal: String,
        val kind: String,
        val createdAt: Long,
        val updatedAt: Long,
        val status: String // active | paused | done | cancelled
    )

    fun setActive(context: Context, goal: String, kind: String = "user") {
        val g = goal.trim().take(1000)
        if (g.isBlank()) return
        if (isBackgroundNoise(g)) return
        val now = System.currentTimeMillis()
        val prev = load(context)
        val m = Mission(
            goal = g,
            kind = kind,
            createdAt = prev?.createdAt ?: now,
            updatedAt = now,
            status = "active"
        )
        save(context, m)
    }

    fun activeGoal(context: Context): String =
        load(context)?.takeIf { it.status == "active" || it.status == "paused" }?.goal.orEmpty()

    fun markDone(context: Context) {
        val m = load(context) ?: return
        save(context, m.copy(status = "done", updatedAt = System.currentTimeMillis()))
    }

    fun markCancelled(context: Context) {
        val m = load(context) ?: return
        save(context, m.copy(status = "cancelled", updatedAt = System.currentTimeMillis()))
    }

    fun pause(context: Context) {
        val m = load(context) ?: return
        if (m.status == "active") save(context, m.copy(status = "paused", updatedAt = System.currentTimeMillis()))
    }

    /** Resume text for continue / fix that — never soul-watch or trading unless that was the goal. */
    fun resumeObjective(context: Context, userSaid: String): String {
        val active = activeGoal(context)
        if (active.isNotBlank()) return active
        return userSaid.trim()
    }

    /** True if this text is background machinery, not a user mission. */
    fun isBackgroundNoise(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("soul-watch") ||
            t.contains("soul_watch") ||
            t.contains("startup-context") ||
            t.contains("startup_recovery") ||
            t.contains("window_event") ||
            t.contains("work=restore_pending") ||
            t.contains("work=soul") ||
            (t.contains("trading context") && t.length < 80)
    }

    /** Block injecting background work into the user-facing turn when a mission is active. */
    fun shouldSuppressBackgroundInjection(context: Context, candidate: String): Boolean {
        if (activeGoal(context).isBlank()) return false
        return isBackgroundNoise(candidate)
    }

    private fun load(context: Context): Mission? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val o = JSONObject(raw)
        Mission(
            goal = o.optString("goal"),
            kind = o.optString("kind", "user"),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
            status = o.optString("status", "active")
        )
    }.getOrNull()

    private fun save(context: Context, m: Mission): Boolean = runCatching {
        val o = JSONObject()
            .put("goal", m.goal)
            .put("kind", m.kind)
            .put("createdAt", m.createdAt)
            .put("updatedAt", m.updatedAt)
            .put("status", m.status)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).commit()
    }.getOrDefault(false)
}
