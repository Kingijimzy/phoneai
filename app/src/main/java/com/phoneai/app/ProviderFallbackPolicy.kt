package com.phoneai.app

/**
 * Keep one selected provider as the default. Only an explicit HTTP 429 rate-limit response
 * permits a backup provider for this command. Authentication errors, unsupported models,
 * timeouts, network errors, empty responses, cancellations, and budget stops must not fan
 * out into more provider requests.
 */
internal object ProviderFallbackPolicy {
    /** Never fan one command across a chain of saved providers. */
    const val MAX_BACKUP_PROVIDERS = 1
    fun shouldTryBackup(error: Throwable): Boolean {
        if (error is InterruptedException) return false
        if (error.message.orEmpty().let {
                it == "Stopped." ||
                    it.startsWith("Stopped before sending") ||
                    it.contains("identical HTTP failures")
            }) return false

        return (error as? AiException)?.httpStatus == 429
    }
}
