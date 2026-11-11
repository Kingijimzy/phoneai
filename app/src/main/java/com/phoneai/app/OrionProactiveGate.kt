package com.phoneai.app

import android.content.Context

/**
 * Proactive Gate for the soul-watch background worker. Before Orion spends a model run acting unasked, the gate
 * weighs whether acting is worth interrupting for: is there anything of value to act on, is Kingi busy, is the
 * phone in a fit state, and has Orion already used its unprompted budget. Pure signals, no wording involved;
 * the model still makes the final call about what to do once the gate opens.
 */
internal object OrionProactiveGate {
    data class Signals(
        val hoursSinceLastInitiative: Double,
        val hourOfDay: Int,
        val minutesSinceOwnerActive: Double,
        val batteryPct: Int,
        val charging: Boolean,
        val brokenTools: Int,
        val openFollowUps: Int,
        val dueFollowUps: Int,
        val newCapabilities: Int,
        val untestedCapabilities: Int,
        val unpromptedToday: Int
    )
    data class Decision(val open: Boolean, val score: Double, val reason: String)

    const val DAILY_BUDGET = 4
    const val OPEN_AT = 1.0

    fun decide(s: Signals): Decision {
        if (s.hoursSinceLastInitiative < 2.0) return Decision(false, 0.0, "acted ${"%.1f".format(s.hoursSinceLastInitiative)}h ago")
        if (s.unpromptedToday >= DAILY_BUDGET) return Decision(false, 0.0, "daily unprompted budget used")
        if (s.batteryPct in 0..15 && !s.charging) return Decision(false, 0.0, "battery low")
        if (s.minutesSinceOwnerActive < 3.0) return Decision(false, 0.0, "Kingi is active right now")
        val value = s.dueFollowUps * 1.2 + s.openFollowUps.coerceAtMost(3) * 0.2 +
            s.brokenTools.coerceAtMost(3) * 0.6 + s.newCapabilities.coerceAtMost(3) * 0.5 +
            s.untestedCapabilities.coerceAtMost(10) * 0.05 + (s.hoursSinceLastInitiative / 24.0).coerceAtMost(0.5)
        // Quiet hours: only urgent value may start unprompted work.
        val need = OPEN_AT * (if (s.hourOfDay in 0..5) 2.0 else 1.0)
        val ok = value >= need
        return Decision(ok, value, if (ok) "value %.1f >= %.1f".format(value, need) else "nothing worth acting on (%.1f < %.1f)".format(value, need))
    }

    fun gather(c: Context): Signals {
        val p = c.getSharedPreferences("phoneai", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val bat = (c.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager)
        val pct = runCatching { bat?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 }.getOrDefault(-1)
        val charging = runCatching { bat?.isCharging ?: false }.getOrDefault(false)
        val followUps = runCatching { OrionFollowUpStore.pending(c) }.getOrDefault(emptyList())
        val awareness = OrionCapabilityAwareness.state(c)
        val day = now / 86_400_000L
        return Signals(
            hoursSinceLastInitiative = (now - p.getLong("initiative_last", 0L)) / 3_600_000.0,
            hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
            minutesSinceOwnerActive = (now - p.getLong("owner_last_active", 0L)) / 60_000.0,
            batteryPct = pct, charging = charging,
            brokenTools = runCatching { OrionSelfModel.degraded(c).size }.getOrDefault(0),
            openFollowUps = followUps.size,
            dueFollowUps = followUps.count { (it.dueAt ?: Long.MAX_VALUE) <= now },
            newCapabilities = awareness.added.size + awareness.removed.size,
            untestedCapabilities = awareness.untested.size,
            unpromptedToday = if (p.getLong("initiative_day", -1) == day) p.getInt("initiative_count", 0) else 0
        )
    }

    fun recordAction(c: Context) {
        val p = c.getSharedPreferences("phoneai", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis(); val day = now / 86_400_000L
        val n = if (p.getLong("initiative_day", -1) == day) p.getInt("initiative_count", 0) else 0
        p.edit().putLong("initiative_last", now).putLong("initiative_day", day).putInt("initiative_count", n + 1).apply()
    }
}
