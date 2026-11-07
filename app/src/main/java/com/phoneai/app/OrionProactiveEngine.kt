package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import java.util.Locale

/**
 * Unified proactive intelligence engine (Ultron Chronos adapted to Android).
 *
 * Orchestrates calendar events, unfinished tasks, owner-mentioned follow-ups,
 * notification awareness, battery context, quiet periods and message throttling
 * into a single spoken interruption at most once per configured interval.
 *
 * Rules:
 * - Quiet period: never speak proactively between startHour and endHour.
 * - Throttle: at most one proactive spoken interruption per proactiveMinIntervalMs.
 * - At most proactiveMaxPerHour per hour.
 * - Battery-aware: if battery is below proactiveBatteryLowThreshold and a long
 *   task is due, suggest charging first.
 * - One useful notification per turn: calendar event, unfinished task, or
 *   follow-up — never more than one, never repetitive.
 */
internal object OrionProactiveEngine {

    /** A single proactive interruption Orion may speak, with reason and urgency. */
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

        // 0. Global gates: proactive pack + core must be on.
        if (!AgentPolicy.enabled(app, "orion_core") || !AgentPolicy.enabled(app, "proactive")) {
            return null
        }

        // 1. Quiet period: do not speak during owner-configured quiet hours.
        if (inQuietPeriod(app, now)) return null

        // 2. Throttle: at most one spoken interruption per configured minimum interval.
        val lastSpoken = Prefs.lastProactiveSpokenAt(app)
        val minInterval = Prefs.proactiveMinIntervalMs(app)
        if (lastSpoken > 0L && now - lastSpoken < minInterval) return null

        // 3. Rate cap: at most proactiveMaxPerHour per hour.
        val maxPerHour = Prefs.proactiveMaxPerHour(app)
        if (maxPerHour <= 0) return null // proactive speaking fully disabled
        val spokenThisHour = countSpokenSince(app, now - 60 * 60 * 1000L, now)
        if (spokenThisHour >= maxPerHour) return null

        // 4. Gather candidate items from all sources.
        val candidates = mutableListOf<ProactiveItem>()

        // 4a. Calendar events starting soon.
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

        // 4b. Unfinished tasks due soon or overdue.
        val tasks = TaskStore.all(app)
        val unfinished = tasks.filter { it.status !in setOf("completed", "done", "failed", "cancelled") }
        for (t in unfinished.take(5)) {
            if (t.dueAt != null && t.dueAt!! <= now + 60 * 60 * 1000L && t.dueAt!! > 0L) {
                val overdue = t.dueAt!! <= now
                candidates += ProactiveItem(
                    kind = "task",
                    title = t.title,
                    body = if (overdue) "Task '${t.title}' was due ${formatAgo(now - t.dueAt!!)} ago and is still open."
                    else "Task '${t.title}' is due ${formatAgo(t.dueAt!! - now)} from now.",
                    urgency = if (overdue || t.priority == "urgent") 2 else 1,
                    dueAtMillis = t.dueAt
                )
            }
        }

        // 4c. Owner-mentioned follow-ups due soon or overdue.
        val followUps = OrionFollowUpStore.pending(app)
        for (f in followUps.take(5)) {
            if (f.dueAt != null && f.dueAt!! <= now + 60 * 60 * 1000L && f.dueAt!! > 0L) {
                val overdue = f.dueAt!! <= now
                candidates += ProactiveItem(
                    kind = "followup",
                    title = f.text.take(60),
                    body = if (overdue) "Follow-up '${f.text}' was due ${formatAgo(now - f.dueAt!!)} ago."
                    else "Follow-up '${f.text}' is due ${formatAgo(f.dueAt!! - now)} from now.",
                    urgency = if (overdue) 2 else 1,
                    dueAtMillis = f.dueAt
                )
            }
        }

        // 4d. Battery: low battery before a long/due task suggests charging first.
        val batteryLowThreshold = Prefs.proactiveBatteryLowThreshold(app)
        if (batteryLowThreshold > 0) {
            val deviceCtx = OrionDeviceContextProvider.summary(app)
            val batteryPct = parseBatteryPercent(deviceCtx)
            val charging = deviceCtx.contains("charging=true")
            if (batteryPct >= 0 && batteryPct <= batteryLowThreshold && !charging) {
                val hasDueSoon = candidates.any { it.dueAtMillis != null && it.dueAtMillis!! <= now + 2 * 60 * 60 * 1000L }
                if (hasDueSoon || unfinished.any { it.priority == "urgent" }) {
                    candidates += ProactiveItem(
                        kind = "battery",
                        title = "Battery low",
                        body = "Battery is at ${batteryPct}% and not charging. Consider plugging in before a long task.",
                        urgency = 1
                    )
                }
            }
        }

        // 4e. Recent safe notifications that look important (from OrionNotificationListener).
        val notifCtx = OrionNotificationListener.contextSummary()
        val importantLines = notifCtx.lineSequence()
            .filter { it.contains("urgent", ignoreCase = true) || it.contains("alert", ignoreCase = true) || it.contains("reminder", ignoreCase = true) }
            .take(2)
        for (line in importantLines) {
            candidates += ProactiveItem(
                kind = "notification",
                title = "Notification",
                body = line.take(160),
                urgency = 1
            )
        }

        // 5. Pick the single most urgent item (ties: earliest due).
        val best = candidates
            .sortedWith(compareByDescending<ProactiveItem> { it.urgency }
                .thenBy { it.dueAtMillis ?: Long.MAX_VALUE })
            .firstOrNull() ?: return null

        // 6. Record the interruption so throttling applies.
        Prefs.setLastProactiveSpokenAt(app, now)
        recordSpoken(app, now)

        return best
    }

    /** Format a millisecond delta as a short human string. */
    private fun formatAgo(deltaMs: Long): String {
        val minutes = deltaMs / 60_000L
        return when {
            minutes < 1 -> "less than a minute"
            minutes < 60 -> "$minutes min"
            else -> {
                val hours = minutes / 60
                val rem = minutes % 60
                if (rem == 0L) "$hours hour${if (hours > 1) "s" else ""}"
                else "$hours hour${if (hours > 1) "s" else ""} $rem min"
            }
        }
    }

    private fun inQuietPeriod(context: Context, now: Long): Boolean {
        val start = Prefs.quietPeriodStartHour(context)
        val end = Prefs.quietPeriodEndHour(context)
        val hour = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
        return if (start < end) hour in start until end
        else hour >= start || hour < end // wraps midnight
    }

    /** Count spoken interruptions since a given time (for the per-hour cap). */
    private fun countSpokenSince(context: Context, since: Long, now: Long): Int {
        val raw = context.getSharedPreferences("orion_proactive_spoken_v1", Context.MODE_PRIVATE)
            .getString("spoken_at", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).count { i ->
                val t = arr.optLong(i, 0L)
                t > since && t <= now
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun recordSpoken(context: Context, now: Long) {
        val raw = context.getSharedPreferences("orion_proactive_spoken_v1", Context.MODE_PRIVATE)
            .getString("spoken_at", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val kept = JSONArray()
        val cutoff = now - 24 * 60 * 60 * 1000L // drop records older than 24h
        (0 until arr.length()).forEach { i ->
            val t = arr.optLong(i, 0L)
            if (t > cutoff) kept.put(t)
        }
        kept.put(now)
        // Cap at 100 entries
        while (kept.length() > 100) kept.remove(0)
        context.getSharedPreferences("orion_proactive_spoken_v1", Context.MODE_PRIVATE).edit()
            .putString("spoken_at", kept.toString()).apply()
    }

    private fun parseBatteryPercent(deviceCtx: String): Int {
        // deviceCtx looks like: "OWNER-GRANTED DEVICE STATE ... battery=42%; ..."
        val m = Regex("battery=(\\d+)%").find(deviceCtx)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }
}
