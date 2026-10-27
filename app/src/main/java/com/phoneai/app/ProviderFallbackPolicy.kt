package com.phoneai.app

/**
 * Cross-provider fallback is deliberately rate-limit-only.
 *
 * Keep the selected provider as the primary. Move to a backup only when that provider
 * explicitly returns HTTP 429 and automatic switching is enabled. Timeouts, empty replies,
 * unsupported models, invalid credentials, network failures, and billing errors must be
 * surfaced to the user rather than silently spending another provider's quota.
 *
 * The caller filters provider eligibility and the shared command request budget bounds attempts.
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
            message.contains("cannot connect") ||
            message.contains("check internet") ||
            message.contains("sockettimeout") ||
            message.contains("reached orion's 15-request budget") ||
            message.contains("paid-provider use is off") ||
            message.contains("blocked the request")) return false

        // Do not infer permission to rotate from message text. Only an explicit HTTP 429
        // response from the current provider permits a backup attempt.
        return error is AiException && error.httpStatus == 429
    }
}
