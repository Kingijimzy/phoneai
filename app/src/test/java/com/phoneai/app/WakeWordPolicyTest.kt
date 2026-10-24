package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordPolicyTest {
    @Test fun sensitivityLowersThresholdMonotonically() {
        assertEquals(0.65f, WakeWordPolicy.thresholdForSensitivity(0), 0.0001f)
        assertEquals(0.375f, WakeWordPolicy.thresholdForSensitivity(50), 0.0001f)
        assertEquals(0.1f, WakeWordPolicy.thresholdForSensitivity(100), 0.0001f)
        assertTrue(WakeWordPolicy.thresholdForSensitivity(80) < WakeWordPolicy.thresholdForSensitivity(20))
        assertEquals(WakeWordPolicy.thresholdForSensitivity(100), WakeWordPolicy.thresholdForSensitivity(1000), 0.0001f)
    }

    @Test fun missingOrFailedOfflineModelUsesFallback() {
        assertFalse(WakeWordPolicy.useOfflineModel(false, false))
        assertFalse(WakeWordPolicy.useOfflineModel(true, true))
        assertTrue(WakeWordPolicy.useOfflineModel(true, false))
    }
}
