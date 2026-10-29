package com.phoneai.app

/**
 * Cross-provider fallback is bounded and failure-aware.
 *
 * Keep the selected provider as the primary, but allow another eligible provider to answer
 * when the primary is rate-limited, times out, returns an empty reply, rejects a model, or
 * reports a transient/server/payload-size failure. Same-provider model recovery runs first.
 *
 * Never rotate for cancellation, exhausted request budgets, authentication, billing, explicit
 * safety blocks, or a general network-unavailable condition. The caller filters provider
 * eligibility and the shared command request budget bounds all attempts.
 */
internal object ProviderFallbackPolicy {
    /** Maximum backup providers considered after the selected primary. */
    const val MAX_BACKUP_PROVIDERS = 2

    private val recoverableHttpStatuses = setOf(
        404, 408, 410, 413, 422, 425, 429, 500, 502, 503, 504
    )

    fun shouldTryBackup(error: Throwable, autoSwitchEnabled: Boolean = true): Boolean {
        if (!autoSwitchEnabled || error is InterruptedException) return false
        val message = error.message.orEmpty().lowercase()
        if (message == "stopped." ||
            message.startsWith("stopped before sending") ||
            message.contains("identical http failures") ||
            message.contains("key rejected") ||
            message.contains("invalid api key") ||
            message.contains("unauthorized") ||
            message.contains("authentication") ||
            message.contains("cannot connect") ||
            message.contains("check internet") ||
            message.contains("reached orion's 15-request budget") ||
            message.contains("paid-provider use is off") ||
            message.contains("blocked the request")) return false

        // Rotate only for known provider/model failures. A generic exception or an
        // unclassified 400 must not silently spend another provider's quota.
        return error is AiException && error.httpStatus?.let { it in recoverableHttpStatuses } == true
    }
}
