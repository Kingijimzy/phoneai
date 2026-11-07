package com.phoneai.app

import android.content.Context

/**
 * Keeps Orion quiet: a proactive notification is posted only after the owner has been idle for 15 minutes
 * and at least 20 minutes after the previous proactive notification. Quiet is a guard on speaking up, never on
 * doing work the owner asked for.
 */
internal object OrionProactiveSpacing {
    const val QUIET_MS = 15 * 60_000L
    const val GAP_MS = 20 * 60_000L
    private const val PREFS = "orion_proactive_spacing"
    private val lock = Any()

    fun allowed(now: Long, lastOwnerAt: Long, lastProactiveAt: Long): Boolean =
        (lastOwnerAt <= 0L || now - lastOwnerAt >= QUIET_MS) &&
            (lastProactiveAt <= 0L || now - lastProactiveAt >= GAP_MS)

    fun noteOwnerActivity(c: Context, now: Long = System.currentTimeMillis()) {
        runCatching { c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("owner_at", now).apply() }
    }

    /** True and records the message time when a proactive message may be posted now. */
    fun tryClaim(c: Context, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        runCatching {
            val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!allowed(now, p.getLong("owner_at", 0L), p.getLong("proactive_at", 0L))) return@synchronized false
            p.edit().putLong("proactive_at", now).apply()
            true
        }.getOrDefault(true)
    }
}
