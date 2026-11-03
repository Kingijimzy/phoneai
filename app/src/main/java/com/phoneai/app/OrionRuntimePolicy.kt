package com.phoneai.app

/** Pure retry rules shared by the runtime and regression tests. */
internal object OrionRuntimePolicy {
    const val MAX_QUEUE = 40
    const val MAX_ATTEMPTS = 4
    const val BASE_BACKOFF_MS = 30_000L
    const val MAX_BACKOFF_MS = 15 * 60_000L

    /** New real work may recover a degraded queue, but must not unpause a stopped service. */
    fun stateAfterEnqueue(current: OrionRuntime.State, queueHasWork: Boolean): OrionRuntime.State =
        if (queueHasWork && current == OrionRuntime.State.DEGRADED) OrionRuntime.State.ACTIVE else current

    /** Attempts are one-based failures already observed. */
    fun retryDelayMs(attempt: Int, baseBackoffMs: Long = BASE_BACKOFF_MS): Long {
        if (attempt <= 0) return 0L
        val shift = (attempt - 1).coerceIn(0, 30)
        return (baseBackoffMs.coerceIn(15_000L, 300_000L) * (1L shl shift)).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** Owner dial is the number of retries allowed; zero disables automatic retries. */
    fun shouldRetry(nextAttempt: Int, maxRetries: Int = MAX_ATTEMPTS - 1): Boolean =
        nextAttempt in 1..maxRetries.coerceIn(0, MAX_ATTEMPTS)


    /** External standing orders are not replayed automatically after an ambiguous process death. */
    fun canReplayAfterProcessDeath(type: OrionRuntime.WorkType): Boolean =
        type != OrionRuntime.WorkType.SOUL_WATCH

    /** Create a bounded retry checkpoint while preserving the original goal and progress cursor. */
    fun retryItem(
        item: OrionRuntime.WorkItem,
        now: Long,
        error: String,
        checkpoint: String = item.checkpoint,
        maxRetries: Int = MAX_ATTEMPTS - 1,
        baseBackoffMs: Long = BASE_BACKOFF_MS
    ): OrionRuntime.WorkItem? {
        val attempts = item.attempts + 1
        if (!shouldRetry(attempts, maxRetries)) return null
        val safeError = error.trim().replace(Regex("\\s+"), " ").take(240).ifBlank { "unknown_failure" }
        return item.copy(
            attempts = attempts,
            notBefore = now + retryDelayMs(attempts, baseBackoffMs),
            checkpoint = checkpoint.trim().take(500),
            lastError = safeError
        )
    }

    /** Returns the time until the earliest queued item is eligible, or null for an empty queue. */
    fun nextEligibleDelayMs(now: Long, notBeforeTimes: List<Long>): Long? =
        notBeforeTimes.minOrNull()?.let { (it - now).coerceAtLeast(0L) }
}