package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/**
 * Ultron-style background life: brief proactive work only when idle.
 * Never injects trading/soul-watch into an active user mission (MissionLock).
 */
internal object OrionAutonomousPulse {
    private const val PREFS = "orion_autonomous_pulse_v1"
    private const val KEY_LAST = "last_pulse_at"
    private const val KEY_ENABLED = "enabled"
    /** Minimum gap between proactive pulses. */
    private const val MIN_INTERVAL_MS = 15 * 60 * 1000L

    data class Pulse(
        val allowed: Boolean,
        val reason: String,
        val suggestedFocus: String?
    )

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    /**
     * Call from a background scheduler / soul tick.
     * If a user mission is active, pulse is suppressed.
     */
    fun evaluate(context: Context, now: Long = System.currentTimeMillis()): Pulse {
        if (!isEnabled(context)) {
            return Pulse(false, "pulse_disabled", null)
        }
        val mission = OrionMissionLock.activeGoal(context)
        if (mission.isNotBlank()) {
            return Pulse(false, "active_mission_blocks_pulse", null)
        }
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L)
        if (now - last < MIN_INTERVAL_MS) {
            return Pulse(false, "interval_not_elapsed", null)
        }
        return Pulse(
            allowed = true,
            reason = "idle",
            suggestedFocus = "brief status: device, pending tasks, optional market headline — one short notification only"
        )
    }

    fun markFired(context: Context, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST, now).apply()
    }

    /** Side-channel note for UI — never replaces chat reply during a mission. */
    fun notificationCopy(focus: String?): String =
        "Orion pulse: ${(focus ?: "checking idle status").take(160)}"

    fun debugState(context: Context): String {
        val o = JSONObject()
            .put("enabled", isEnabled(context))
            .put("mission", OrionMissionLock.activeGoal(context))
            .put("lastPulseAt", context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L))
        return o.toString()
    }
}
