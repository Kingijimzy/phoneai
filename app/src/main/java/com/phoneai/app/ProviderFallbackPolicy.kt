package com.phoneai.app

/**
 * Cross-provider fallback is bounded and failure-aware.
 *
 * Keep the selected provider as the primary, but allow another eligible provider to answer
 * when the primary is rate-limited, times out, returns an empty reply, rejects a model, or
 * reports a transient/server/payload-size failure. Same-provider model recovery runs first.
 *
 * Never rotate for cancellation, exhausted request budgets, authentication, explicit safety
 * blocks, or a general network-unavailable condition. A billing/credit failure may rotate only
 * to another provider already configured and approved by ProviderBillingPolicy; the caller
 * filters eligibility before this policy is consulted.
 */
internal object ProviderFallbackPolicy {
    /** Maximum backup providers considered after the selected primary. */
    const val MAX_BACKUP_PROVIDERS = 2

    private val recoverableHttpStatuses = setOf(
        402, 404, 408, 410, 413, 422, 425, 429, 500, 502, 503, 504
    )
    private val unsupportedToolMarkers = listOf(
        "does not support tools", "doesn't support tools",
        "does not support tool calling", "doesn't support tool calling",
        "tool calling is not supported", "tool calls are not supported",
        "function calling is not supported", "tools are not supported",
        "tool use is not supported", "tool_choice is not supported",
        "unsupported tool type", "unknown parameter: tools",
        "unrecognized request argument: tools", "tools is not supported"
    )

    /** True only for an explicit HTTP 400 rejection of the native tool protocol. */
    fun isToolProtocolUnsupported(error: Throwable): Boolean {
        if (error !is AiException || error.httpStatus != 400) return false
        val messages = generateSequence(error as Throwable?) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }.lowercase()
        return unsupportedToolMarkers.any(messages::contains)
    }

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

        // A provider that explicitly rejects native tool calling is also recoverable:
        // the same registered tool contract can be sent to another configured provider.
        // Generic HTTP 400s remain non-recoverable and never spend another provider's quota.
        if (isToolProtocolUnsupported(error)) return true
        return error is AiException && error.httpStatus?.let { it in recoverableHttpStatuses } == true
    }
}
