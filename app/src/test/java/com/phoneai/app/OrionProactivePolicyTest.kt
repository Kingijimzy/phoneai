package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionProactivePolicyTest {
    @Test fun proactiveHeartbeatRequiresExplicitCoreEnablement() {
        assertTrue(OrionProactivePolicy.shouldRun(coreEnabled = true, proactiveEnabled = true))
        assertFalse(OrionProactivePolicy.shouldRun(coreEnabled = false, proactiveEnabled = true))
        assertFalse(OrionProactivePolicy.shouldRun(coreEnabled = true, proactiveEnabled = false))
        assertFalse(OrionProactivePolicy.shouldRun(coreEnabled = false, proactiveEnabled = false))
    }

    @Test fun heartbeatUsesBatteryAwareMinimumIntervalAndBoundedRetries() {
        assertTrue(OrionProactivePolicy.INTERVAL_MINUTES >= 15L)
        assertTrue(OrionProactivePolicy.FLEX_MINUTES in 1L until OrionProactivePolicy.INTERVAL_MINUTES)
        assertTrue(OrionProactivePolicy.MAX_RETRIES in 1..5)
    }
}
