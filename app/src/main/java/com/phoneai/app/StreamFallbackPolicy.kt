package com.phoneai.app

/** Prevents cross-provider retry after visible output or cancellation. */
internal object StreamFallbackPolicy {
    fun mayTryAnotherProvider(emittedVisibleText: Boolean, cancelled: Boolean): Boolean =
        !emittedVisibleText && !cancelled
}
