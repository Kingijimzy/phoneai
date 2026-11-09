package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionAutonomousPulseTest {

    @Test
    fun backgroundNoiseStillDetected() {
        assertTrue(OrionMissionLock.isBackgroundNoise("work=soul-watch; key=soul-watch"))
        assertFalse(OrionMissionLock.isBackgroundNoise("scrape apkvision for file size"))
    }

    @Test
    fun recoveryStillSuggestsAlternate() {
        val p = OrionForcedRecovery.afterFailure(
            "atletico vs barca score",
            "live_sports",
            "error: down",
            0
        )
        assertTrue(p.shouldRetry)
        assertTrue(p.nextToolHint!!.contains("web", ignoreCase = true))
    }
}
