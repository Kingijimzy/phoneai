package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionContinuousWatcherPolicyTest {
    @Test fun liveObservationRequiresBothOwnerControlledPacksAndConnectedService() {
        assertTrue(OrionContinuousWatcherPolicy.shouldObserve(true, true, true, Long.MAX_VALUE))
        assertFalse(OrionContinuousWatcherPolicy.shouldObserve(false, true, true, Long.MAX_VALUE))
        assertFalse(OrionContinuousWatcherPolicy.shouldObserve(true, false, true, Long.MAX_VALUE))
        assertFalse(OrionContinuousWatcherPolicy.shouldObserve(true, true, false, Long.MAX_VALUE))
    }

    @Test fun liveObservationCoalescesRapidWindowEvents() {
        assertFalse(OrionContinuousWatcherPolicy.shouldObserve(true, true, true, 0L))
        assertFalse(OrionContinuousWatcherPolicy.shouldObserve(true, true, true, OrionContinuousWatcherPolicy.COOLDOWN_MS - 1))
        assertTrue(OrionContinuousWatcherPolicy.shouldObserve(true, true, true, OrionContinuousWatcherPolicy.COOLDOWN_MS))
    }
}
