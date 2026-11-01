package com.phoneai.app

import android.content.Context

/** Short-lived, coarse sensor summaries; raw sensor vectors are never persisted. */
internal object OrionSensorContextStore {
    private const val PREFS = "orion_sensor_context_v1"
    private const val KEY_SUMMARY = "motion_summary"
    private const val KEY_TIME = "motion_time"
    private const val TTL_MS = 15L * 60L * 1000L

    fun recordMotion(context: Context, summary: String, now: Long = System.currentTimeMillis()): Boolean {
        if (!OrionSensorConsent.read(context, OrionSensorScope.MOTION, now).active()) return false
        val safe = summary.replace(Regex("[\\r\\n]+"), " ").take(180)
        if (safe.isBlank()) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SUMMARY, safe).putLong(KEY_TIME, now).commit()
    }

    fun summary(context: Context, now: Long = System.currentTimeMillis()): String {
        if (!OrionSensorConsent.read(context, OrionSensorScope.MOTION, now).active()) {
            clear(context)
            return ""
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = prefs.getLong(KEY_TIME, 0L)
        if (time <= 0L || now - time > TTL_MS || time > now) {
            clear(context)
            return ""
        }
        val value = prefs.getString(KEY_SUMMARY, "").orEmpty()
        return if (value.isBlank()) "" else
            "OWNER-GRANTED SENSOR CONTEXT (coarse motion estimate; sampled while Orion was foregrounded; expires after 15 minutes): $value"
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    internal fun isFresh(timestamp: Long, now: Long = System.currentTimeMillis()): Boolean =
        timestamp > 0L && timestamp <= now && now - timestamp <= TTL_MS
}
