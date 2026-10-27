package com.phoneai.app

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Decides when Orion should leave a failing provider and try an eligible saved backup.
 *
 * Rotate on transient provider failures (rate limits, server errors, timeouts, empty replies).
 * Never rotate for cancellation, exhausted request budget, invalid credentials, billing gates,
 * or a clearly offline device: those cases either cannot be helped by another provider or
 * should not silently consume another provider's quota.
 */
internal object ProviderFallbackPolicy {
    /** Maximum backup providers considered after the selected primary. */
    const val MAX_BACKUP_PROVIDERS = 2

    fun shouldTryBackup(error: Throwable, autoSwitchEnabled: Boolean = true): Boolean {
        if (!autoSwitchEnabled) return false

        val chain = generateSequence(error) { it.cause }.take(6).toList()
        if (chain.any { it is InterruptedException || it is InterruptedIOException && it !is SocketTimeoutException }) {
            return false
        }

        val message = chain.joinToString(" | ") { it.message.orEmpty().lowercase() }
        val types = chain.joinToString(" | ") { it.javaClass.simpleName.lowercase() }

        // These failures are not repaired by switching providers and must not spend more quota.
        if (message == "stopped." ||
            message.startsWith("stopped before sending") ||
            message.contains("identical http failures") ||
            message.contains("reached orion's 15-request budget") ||
            message.contains("15-request budget") ||
            message.contains("paid-provider use is off") ||
            message.contains("blocked the request") ||
            message.contains("api key rejected") ||
            message.contains("invalid api key") ||
            message.contains("unauthorized") ||
            message.contains("authentication") ||
            message.contains("out of credit") ||
            message.contains("billing") ||
            message.contains("insufficient balance") ||
            message.contains("quota project") ||
            message.contains("user cancelled") ||
            message.contains("user canceled")) return false

        // A missing network/DNS route affects every provider. A read timeout is different:
        // one provider may be stalled while another is healthy.
        if (chain.any { it is UnknownHostException } ||
            message.contains("no internet connection") ||
            message.contains("network is unreachable") ||
            message.contains("check your internet connection")) return false

        if (chain.any { it is SocketTimeoutException } ||
            types.contains("sockettimeoutexception") ||
            message.contains("timed out") ||
            message.contains("timeout") ||
            message.contains("empty response") ||
            message.contains("returned no readable chat text") ||
            message.contains("temporarily unavailable") ||
            message.contains("service unavailable") ||
            message.contains("server overloaded") ||
            message.contains("overloaded")) return true

        val status = (error as? AiException)?.httpStatus
        if (status == 429 || status in 500..599) return true

        // Some HTTP/provider adapters wrap the status in a RuntimeException rather than AiException.
        if (Regex("\\bhttp\\s*(429|5\\d\\d)\\b").containsMatchIn(message)) return true

        return false
    }
}
