package com.phoneai.app

/**
 * Event-driven decision policy for Orion's background runtime.
 * It does not call an AI provider, execute phone actions, or schedule a polling loop.
 * It converts explicit local events into bounded, deduplicated runtime work.
 */
internal class OrionDecisionScheduler(
    private val runtime: OrionRuntime,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private var started = false
    private var lastWindowKey = ""
    private var lastWindowAt = 0L
    private var lastTaskKey = ""
    private var lastTaskAt = 0L
    private var lastSoulAt = 0L

    data class Decision(val accepted: Boolean, val reason: String, val workType: OrionRuntime.WorkType?)

    fun onServiceReady(): Decision = synchronized(lock) {
        if (started) return@synchronized Decision(false, "already_started", null)
        started = true
        runtime.enqueue(OrionRuntime.WorkType.RESTORE_PENDING, "startup-context", 8, goal = "Restore the active conversation context", checkpoint = "startup_recovery_queued")
        runtime.enqueue(OrionRuntime.WorkType.CAPABILITY_RECHECK, "service-ready", 6, goal = "Verify live capability readiness", checkpoint = "service_connected")
        Decision(true, "startup_recovery_queued", OrionRuntime.WorkType.RESTORE_PENDING)
    }

    fun onWindowChanged(packageName: String?): Decision = synchronized(lock) {
        val key = packageName.orEmpty().trim().take(100).ifBlank { "unknown-window" }
        val now = clock()
        if (key == lastWindowKey && now - lastWindowAt < WINDOW_DEBOUNCE_MS) {
            return@synchronized Decision(false, "duplicate_window_event", null)
        }
        lastWindowKey = key
        lastWindowAt = now
        // The soul loop rides on real window events (the phone is in use), at most once per tick window.
        if (SoulPolicy.due(lastSoulAt, now)) {
            lastSoulAt = now
            runtime.enqueue(OrionRuntime.WorkType.SOUL_WATCH, "soul-watch", 2, goal = "Inspect local readiness and configured standing orders", checkpoint = "window_event")
        }
        val accepted = runtime.enqueue(OrionRuntime.WorkType.CAPABILITY_RECHECK, "window:$key", 1, goal = "Recheck capability readiness after a window change", checkpoint = "window:$key")
        Decision(accepted, if (accepted) "window_recheck_queued" else "already_queued_or_capacity", OrionRuntime.WorkType.CAPABILITY_RECHECK)
    }

    /** Safe external signals (for example a filtered notification) enter the same durable queue. */
    fun onExternalPresenceEvent(eventKey: String, description: String): Decision = synchronized(lock) {
        val key = eventKey.trim().take(100)
        val detail = description.trim().take(120)
        if (key.isBlank() || detail.isBlank()) return@synchronized Decision(false, "invalid_presence_event", null)
        val now = clock()
        val dedupe = "$key:${now / PRESENCE_DEDUPE_WINDOW_MS}"
        val accepted = runtime.enqueue(
            OrionRuntime.WorkType.PRESENCE_EVENT, dedupe, 3,
            goal = "Record safe external event: $detail",
            checkpoint = "source=system_event; content=not_retained"
        )
        Decision(accepted, if (accepted) "presence_event_queued" else "duplicate_or_capacity", OrionRuntime.WorkType.PRESENCE_EVENT)
    }

    /** Called only after an explicit user-requested agent task ends. */
    fun onTaskFinished(task: String, success: Boolean): Decision = synchronized(lock) {
        val key = ExperiencePolicy.gist(task).take(100)
        if (key.isBlank()) return@synchronized Decision(false, "empty_task_key", null)
        val now = clock()
        if (key == lastTaskKey && now - lastTaskAt < TASK_DEBOUNCE_MS) {
            return@synchronized Decision(false, "duplicate_task_outcome", null)
        }
        lastTaskKey = key
        lastTaskAt = now
        val suffix = if (success) "success" else "failure"
        val accepted = runtime.enqueue(OrionRuntime.WorkType.LEARN_FROM_RESULT, "$key:$suffix", if (success) 4 else 5, goal = task, checkpoint = "task_outcome:$suffix")
        Decision(accepted, if (accepted) "task_outcome_queued" else "already_queued_or_capacity", OrionRuntime.WorkType.LEARN_FROM_RESULT)
    }

    companion object {
        const val WINDOW_DEBOUNCE_MS = 2_000L
        const val TASK_DEBOUNCE_MS = 5_000L
        const val PRESENCE_DEDUPE_WINDOW_MS = 60_000L
    }
}
