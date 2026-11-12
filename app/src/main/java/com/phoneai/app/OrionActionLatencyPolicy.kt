package com.phoneai.app

/**
 * Conservative Android interaction settling for the POCO F3 class of device.
 * These are minimum hand-off delays, not claims that an app has finished loading.
 * Long transitions must use wait_for / screen verification rather than fixed sleeps.
 */
internal object OrionActionLatencyPolicy {
    const val LAUNCH_SETTLE_MS = 450L
    const val CLICK_SETTLE_MS = 180L
    const val WAIT_POLL_MS = 250L

    fun launchDelay(requestedMs: Long): Long = requestedMs.coerceIn(0L, 2_000L)
}
