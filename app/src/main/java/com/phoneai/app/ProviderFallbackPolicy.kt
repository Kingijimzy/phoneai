package com.phoneai.app

/**
 * Provider rotation rules. Any provider-side failure (rate limit, timeout, empty reply,
 * overload, retired model, rejected key, no connection) rotates to the next saved provider.
 * A user cancel or the per-command request budget never rotates. Quota-saver mode limits
 * how many backups Nim.chat tries.
 */
internal object ProviderFallbackPolicy {
    fun shouldTryBackup(error: Throwable): Boolean {
        val msg = error.message.orEmpty()
        if (msg == "Stopped." || msg.startsWith("Stopped before sending") || msg.contains("identical HTTP failures")) return false
        if (error is InterruptedException) return false
        return error is AiException || error is java.io.IOException
    }
}
