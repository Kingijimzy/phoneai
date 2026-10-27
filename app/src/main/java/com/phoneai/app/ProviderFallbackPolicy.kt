package com.phoneai.app

/**
 * Cross-provider fallback for transient provider failures. Authentication, billing, offline,
 * cancellation, explicit user stops, and exhausted request budgets must not trigger rotation.
 */
internal object ProviderFallbackPolicy {
    /** Maximum backup providers considered after the selected primary. */
    const val MAX_BACKUP_PROVIDERS = 2

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
            message.contains("billing") ||
            message.contains("out of credit") ||
            message.contains("cannot connect") ||
            message.contains("check internet") ||
            message.contains("unknownhost") ||
            message.contains("sockettimeout") ||
            message.contains("reached orion's 15-request budget") ||
            message.contains("paid-provider use is off") ||
            message.contains("blocked the request")) return false

        // 409 is provider-specific; rotate only when its response is transient/conflict-like.
        if (error is AiException) {
            val status = error.httpStatus
            return status == 429 || status == 408 || status == 425 ||
                status == 500 || status == 502 || status == 503 || status == 504 ||
                (status == 409 && (message.contains("temporar") ||
                    message.contains("conflict") || message.contains("overload") ||
                    message.contains("try again")))
        }
        val transient = listOf("timeout", "timed out", "empty response", "empty reply",
            "temporarily unavailable", "overloaded", "try again", "connection reset")
        return transient.any { message.contains(it) }
    }
}
