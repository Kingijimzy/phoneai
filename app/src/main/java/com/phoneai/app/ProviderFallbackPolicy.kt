package com.phoneai.app

/**
 * Rotate to the next eligible saved provider for recoverable provider/model failures.
 * Never blindly replay requests for invalid credentials, network failures, timeouts,
 * cancellation, or command-budget exhaustion. The caller filters provider eligibility and
 * the shared command request budget bounds the total number of HTTP attempts.
 */
internal object ProviderFallbackPolicy {
    /** Zero means no additional provider fan-out; otherwise this is informational legacy metadata. */
    const val MAX_BACKUP_PROVIDERS = 14

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
            message.contains("sockettimeout") ||
            message.contains("timed out") ||
            message.contains("request timed out") ||
            message.contains("no response before timeout") ||
            message.contains("reached orion's 15-request budget") ||
            message.contains("paid-provider use is off") ||
            message.contains("blocked the request")) return false

        if (error is AiException && error.httpStatus == 429) return true
        return listOf(
            "empty response", "empty content", "no chat model worked", "no vision model worked",
            "unsupported model", "retired", "out of credit", "temporarily busy",
            "http 404", "http 410", "http 422", "http 500", "http 502",
            "http 503", "http 504", "error 402", "error 404", "error 410", "error 422"
        ).any { message.contains(it) }
    }
}
