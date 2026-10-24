package com.phoneai.app

/** Per-command network request budget and identical HTTP-failure stop policy. */
internal class RequestBudgetGuard(val maxRequests: Int = 15) {
    private val lock = Any()
    private var requests = 0
    private val failureCounts = HashMap<String, Int>()

    init { require(maxRequests > 0) }

    fun beginCommand() = synchronized(lock) {
        requests = 0
        failureCounts.clear()
    }

    /** Atomically reserves one HTTP attempt; false means the caller must not send it. */
    fun reserveRequest(): Boolean = synchronized(lock) {
        if (requests >= maxRequests) false else {
            requests++
            true
        }
    }

    fun requestCount(): Int = synchronized(lock) { requests }

    /** Stores only the caller's opaque signature, never the request/response payload. */
    fun recordFailure(signature: String): Int = synchronized(lock) {
        val count = (failureCounts[signature] ?: 0) + 1
        failureCounts[signature] = count
        count
    }
}
