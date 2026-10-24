package com.phoneai.app

/** Pure wake-word policy, kept separate so sensitivity/fallback behavior is unit-testable. */
internal object WakeWordPolicy {
    fun thresholdForSensitivity(value: Int): Float =
        (0.65f - value.coerceIn(0, 100) * 0.0055f).coerceIn(0.1f, 0.65f)

    fun useOfflineModel(modelInstalled: Boolean, failedThisSession: Boolean): Boolean =
        modelInstalled && !failedThisSession
}
