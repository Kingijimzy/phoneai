package com.phoneai.app

import android.content.Context

/** Daily minute budget for Gemini Live so a long call can never silently burn the whole quota. */
internal object GeminiLiveBudget {
    const val DEFAULT_DAILY_MINUTES = 30
    private const val PREFS = "gemini_live_budget"

    fun remainingMs(usedMs: Long, budgetMinutes: Int): Long = (budgetMinutes.coerceAtLeast(0) * 60_000L - usedMs).coerceAtLeast(0L)

    /** The session ceiling is the normal limit, shortened to whatever is left of today's budget. */
    fun sessionLimitMs(usedMs: Long, budgetMinutes: Int): Long = minOf(GeminiLivePolicy.SESSION_LIMIT_MS, remainingMs(usedMs, budgetMinutes))

    fun label(remainingMs: Long): String = "${(remainingMs + 59_999L) / 60_000L} min of Live left today"

    private fun dayKey(nowMs: Long) = (nowMs + java.util.TimeZone.getDefault().getOffset(nowMs)) / 86_400_000L

    fun usedToday(c: Context, nowMs: Long = System.currentTimeMillis()): Long {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.getLong("day", -1) == dayKey(nowMs)) p.getLong("used", 0L) else 0L
    }

    fun budgetMinutes(c: Context): Int = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("budget", DEFAULT_DAILY_MINUTES)

    fun record(c: Context, elapsedMs: Long, nowMs: Long = System.currentTimeMillis()) {
        if (elapsedMs <= 0) return
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("day", dayKey(nowMs)).putLong("used", usedToday(c, nowMs) + elapsedMs).apply()
    }
}
