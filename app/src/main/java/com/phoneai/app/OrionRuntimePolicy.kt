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
    fun retryDelayMs(attempt: Int): Long {
        if (attempt <= 0) return 0L
        val shift = (attempt - 1).coerceIn(0, 30)
        return (BASE_BACKOFF_MS * (1L shl shift)).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** The final (MAX_ATTEMPTS-th) failure abandons that item. */
    fun shouldRetry(nextAttempt: Int): Boolean = nextAttempt in 1 until MAX_ATTEMPTS

    /** Returns the time until the earliest queued item is eligible, or null for an empty queue. */
    fun nextEligibleDelayMs(now: Long, notBeforeTimes: List<Long>): Long? =
        notBeforeTimes.minOrNull()?.let { (it - now).coerceAtLeast(0L) }
}