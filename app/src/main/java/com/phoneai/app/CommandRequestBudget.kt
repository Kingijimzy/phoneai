package com.phoneai.app

/**
 * Per-user-command HTTP budget. A reservation is consumed before opening a connection,
 * so connection failures and model-list requests count just like successful requests.
 */
internal class CommandRequestBudget(
    private val maxRequests: Int = 15,
    private val maxRequestsProvider: (() -> Int)? = null
) {
    private var usedRequests = 0
    private val repeatedFailures = HashMap<String, Int>()

    init {
        require(maxRequests > 0) { "maxRequests must be positive" }
    }

    @Synchronized
    fun tryReserve(): Boolean {
        val limit = (maxRequestsProvider?.invoke() ?: maxRequests).coerceAtLeast(1)
        if (usedRequests >= limit) return false
        usedRequests++
        return true
    }

    @Synchronized
    fun used(): Int = usedRequests

    /** Returns the new consecutive count for this exact failure signature. */
    @Synchronized
    fun noteFailure(signature: String): Int {
        val next = (repeatedFailures[signature] ?: 0) + 1
        repeatedFailures[signature] = next
        return next
    }

    @Synchronized
    fun reset() {
        usedRequests = 0
        repeatedFailures.clear()
    }
}
