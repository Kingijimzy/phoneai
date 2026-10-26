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

    data class Decision(val accepted: Boolean, val reason: String, val workType: OrionRuntime.WorkType?)

    fun onServiceReady(): Decision = synchronized(lock) {
        if (started) return@synchronized Decision(false, "already_started", null)
        started = true
        runtime.enqueue(OrionRuntime.WorkType.RESTORE_PENDING, "startup-context", 8)
        runtime.enqueue(OrionRuntime.WorkType.CAPABILITY_RECHECK, "service-ready", 6)
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
        val accepted = runtime.enqueue(OrionRuntime.WorkType.CAPABILITY_RECHECK, "window:$key", 1)
        Decision(accepted, if (accepted) "window_recheck_queued" else "already_queued_or_capacity", OrionRuntime.WorkType.CAPABILITY_RECHECK)
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
        val accepted = runtime.enqueue(OrionRuntime.WorkType.LEARN_FROM_RESULT, "$key:$suffix", if (success) 4 else 5)
        Decision(accepted, if (accepted) "task_outcome_queued" else "already_queued_or_capacity", OrionRuntime.WorkType.LEARN_FROM_RESULT)
    }

    companion object {
        const val WINDOW_DEBOUNCE_MS = 2_000L
        const val TASK_DEBOUNCE_MS = 5_000L
    }
}
