package com.phoneai.app

import android.content.Context
import java.util.Calendar
import java.util.TimeZone

/** Pattern policy uses only distinct local days and broad time windows, never app identities. */
internal object HabitRoutinePolicy {
    const val WINDOW_DAYS = 7
    const val MIN_ACTIVE_DAYS = 3
    const val SUGGESTION_COOLDOWN_MS = 72L * 60L * 60L * 1000L

    fun dayPart(hour: Int): String = when (hour) {
        in 5..10 -> "morning"
        in 11..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }

    fun shouldSuggest(activeDays: Int, lastSuggestedAt: Long, now: Long): Boolean =
        activeDays >= MIN_ACTIVE_DAYS &&
            lastSuggestedAt >= 0L && lastSuggestedAt <= now &&
            (lastSuggestedAt == 0L || now - lastSuggestedAt >= SUGGESTION_COOLDOWN_MS)
}

/**
 * Consent-gated habit discovery. It stores only category + daypart + local-day aggregates.
 * It proposes routines; it never starts one. Background execution requires a separate explicit
 * owner-created routine and the existing AUTOPILOT/readiness gates.
 */
internal object HabitRoutineEngine {
    private const val PREFS = "orion_habit_routines_v1"
    private val categories = setOf(
        "communication", "social", "media", "browsing",
        "navigation", "productivity", "shopping", "gaming"
    )

    @Synchronized fun record(context: Context, category: String, now: Long): Boolean {
        if (category !in categories) return false
        val local = Calendar.getInstance().apply { timeInMillis = now }
        val part = HabitRoutinePolicy.dayPart(local.get(Calendar.HOUR_OF_DAY))
        val day = localEpochDay(local)
        val key = "days_${category}_${part}"
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val days = prefs.getStringSet(key, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .filter { it in (day - HabitRoutinePolicy.WINDOW_DAYS + 1)..day }
            .toMutableSet()
        days.add(day)
        return prefs.edit().putStringSet(key, days.map { it.toString() }.toSet()).commit()
    }

    @Synchronized fun suggestion(context: Context, now: Long = System.currentTimeMillis()): String? {
        val app = context.applicationContext
        val appUsage = OrionSensorConsent.read(app, OrionSensorScope.APP_USAGE, now)
        val chronos = OrionSensorConsent.read(app, OrionSensorScope.CHRONOS_AWARENESS, now)
        if (!appUsage.active() || !chronos.active() || chronos.foregroundOnly) return null
        if (!AgentPolicy.enabled(app, "orion_core") || !AgentPolicy.enabled(app, "proactive")) return null

        val local = Calendar.getInstance().apply { timeInMillis = now }
        val day = localEpochDay(local)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (category in categories.sorted()) {
            for (part in listOf("morning", "afternoon", "evening", "night")) {
                val key = "days_${category}_${part}"
                val activeDays = prefs.getStringSet(key, emptySet()).orEmpty()
                    .mapNotNull { it.toLongOrNull() }
                    .count { it in (day - HabitRoutinePolicy.WINDOW_DAYS + 1)..day }
                val shownKey = "suggested_${category}_${part}"
                val lastShown = prefs.getLong(shownKey, 0L)
                if (!HabitRoutinePolicy.shouldSuggest(activeDays, lastShown, now)) continue
                val saved = prefs.edit().putLong(shownKey, now).commit()
                if (!saved) return null
                return "Habit pattern: Orion observed the broad $category category during the $part time window on $activeDays of the last 7 days. Would you like me to prepare an optional routine for this period? It will not run in the background unless you explicitly enable it."
            }
        }
        return null
    }

    @Synchronized fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun localEpochDay(local: Calendar): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }
        return utc.timeInMillis / 86_400_000L
    }
}
