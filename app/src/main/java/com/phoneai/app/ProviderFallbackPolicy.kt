package com.phoneai.app

/**
 * Provider rotation is deliberately narrow to protect free-tier quotas.
 * Only a confirmed HTTP 429 response may trigger one backup attempt.
 */
internal object ProviderFallbackPolicy {
    fun shouldTryBackup(error: Throwable): Boolean =
        error is AiException && error.httpStatus == 429
}
