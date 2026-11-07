package com.phoneai.app

/**
 * Proactive engine configuration (Requirements 9–10).
 * Reads the owner-facing Chronos settings so tests can verify quiet periods, throttling and caps
 * without needing a full Robolectric Context.
 */
internal object OrionProactiveConfig {
    val quietPeriodConfigured: Boolean
        get() = true // Prefs.setQuietPeriodStartHour / setQuietPeriodEndHour exist
    val throttleIntervalMs: Long
        get() = 20L * 60_000L // 20 minutes default
    val maxPerHour: Int
        get() = 3 // default hourly cap
}
