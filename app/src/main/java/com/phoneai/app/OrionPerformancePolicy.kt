package com.phoneai.app

/**
 * Small, deterministic limits for low-memory devices. Kept Android-free so the
 * bounds and pacing rules can be regression-tested on the JVM.
 */
internal object OrionPerformancePolicy {
    const val MAX_SCREENSHOT_WIDTH = 540
    const val MAX_SCREENSHOT_QUEUE = 2

    fun screenshotWidth(sourceWidth: Int): Int =
        sourceWidth.coerceAtLeast(1).coerceAtMost(MAX_SCREENSHOT_WIDTH)

    /** Android PowerManager thermal status values: 0 none, 1 light, 2 moderate, 3 severe, 4+ critical. */
    fun orbFrameDelayMs(powerSave: Boolean, thermalStatus: Int): Long = when {
        thermalStatus >= 3 -> 160L
        thermalStatus == 2 -> 90L
        powerSave -> 100L
        else -> 50L
    }
}
