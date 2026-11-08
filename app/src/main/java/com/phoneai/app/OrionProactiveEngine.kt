package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import java.util.Locale

/**
 * Unified proactive intelligence engine (Ultron Chronos adapted to Android).
 *
 * Orchestrates calendar events, unfinished tasks, owner-mentioned follow-ups,
 * notification awareness, battery context into proactive spoken interruptions.
 *
 * Rules:
 * - No quiet period gate: proactive engine can speak at any time unless the Boss disables it.
 * - No throttle: proactive engine speaks whenever relevant items exist (not limited to one per interval).
 * - Battery-aware: if battery is low and a long task is due, suggest charging first.
 * - One useful item per evaluation cycle: calendar event, unfinished task, or follow-up.
 */
internal object OrionProactiveEngine {

    /** A single proactive interruption NEXUS may speak, with reason and urgency. */
    data class ProactiveItem(
        val kind: String, // "calendar", "task", "followup", "battery", "notification"
        val title: String,
        val body: String,
        val urgency: Int, // 0=low, 1=medium, 2=high
        val dueAtMillis: Long? = null
    )

    /** Evaluate everything and return at most one item to speak, or null. */
    fun evaluate(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): ProactiveItem? {
        val app = context.applicationContext

        // Global gate: proactive pack + core must be on.
        if (!AgentPolicy.enabled(app, "orion_core") && !AgentPolicy.enabled(app, "nexus_core")) {
            return null
        }
        if (!AgentPolicy.enabled(app, "proactive")) {
            return null
        }

        // Gather candidate items from all sources.
        val candidates = mutableListOf<ProactiveItem>()

        // Calendar events starting soon.
        val lookahead = Prefs.calendarLookaheadHours(app)
        val soonEvents = OrionCalendarMonitor.upcoming(app, lookahead, now)
            .filter { it.minutesFromNow > 0 && it.minutesFromNow <= 120 }
            .take(3)
        for (e in soonEvents) {
            candidates += ProactiveItem(
                kind = "calendar",
                title = e.title,
                body = "You have ${e.title} starting ${e.minutesFromNow} minutes from now" +
                    (if (e.location.isNullOrBlank()) "" else " at ${e.location}") + ".",
                urgency = if (e.minutesFromNow <= 15) 2 else 1,
                dueAtMillis = e.startsAtMillis
            )
        }

        // Unfinished tasks due soon or overdue.
        val tasks = TaskStore.all(app)
        val unfinished = tasks.filter { it.status !in setOf("completed", "done", "failed", "cancelled") }
        for (t in unfinished.take(5)) {
            if (t.dueAt != null && t.dueAt!! <= now + 60 * 60 * 1000L && t.dueAt!! > 0L) {
                candidates += ProactiveItem(
                    kind = "task",
                    title = t.title,
                    body = "Your task ${t.title} is due soon or overdue.",
                    urgency = if (t.dueAt!! <= now) 2 else 1,
                    dueAtMillis = t.dueAt
                )
            }
        }

        // Follow-ups (pending store not yet implemented; reserved for future use)
        // val followups = FollowUpStore.pending(app)

        // Battery context.
        val deviceCtx = OrionDeviceContextProvider.summary(app)
        val batteryPercent = parseBatteryPercent(deviceCtx)
        if (batteryPercent > 0 && batteryPercent < 20) {
            candidates += ProactiveItem(
                kind = "battery",
                title = "Battery low",
                body = "Battery is at ${batteryPercent}%. Consider charging soon.",
                urgency = 1
            )
        }

        // Return the most urgent item.
        return candidates.maxByOrNull { it.urgency }
    }

    /** Get all pending proactive items (for context integration). */
    fun getPendingNotifications(context: Context): List<ProactiveItem> {
        return evaluate(context)?.let { listOf(it) } ?: emptyList()
    }

    private fun parseBatteryPercent(deviceCtx: String): Int {
        val m = Regex("battery=(\\d+)%").find(deviceCtx)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }
}