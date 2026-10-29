package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StandingOrderPolicyTest {
    @Test fun schedulerUsesBoundedRetryBackoffAndStopsAfterThreeFailures() {
        assertEquals(5 * 60_000L, StandingOrderPolicy.retryDelay(1))
        assertEquals(15 * 60_000L, StandingOrderPolicy.retryDelay(2))
        assertEquals(60 * 60_000L, StandingOrderPolicy.retryDelay(3))
        val now = 10_000_000L
        val firstFailure = StandingOrder("brief", 60, now, "digest", true, 1, "error", now + StandingOrderPolicy.retryDelay(1))
        org.junit.Assert.assertFalse(StandingOrderPolicy.due(firstFailure, now + 1))
        org.junit.Assert.assertTrue(StandingOrderPolicy.due(firstFailure, now + StandingOrderPolicy.retryDelay(1)))
        val paused = firstFailure.copy(enabled = false, nextRunAt = now)
        org.junit.Assert.assertFalse(StandingOrderPolicy.due(paused, now + 1))
        val exhausted = firstFailure.copy(consecutiveFailures = StandingOrderPolicy.MAX_CONSECUTIVE_FAILURES, nextRunAt = now)
        org.junit.Assert.assertFalse(StandingOrderPolicy.due(exhausted, now + 1))
    }

    @Test fun legacyStandingOrderDefaultsToEnabledAndIntervalFallbackRemainsCompatible() {
        val legacy = StandingOrder("brief", 60, 1_000L, "")
        org.junit.Assert.assertTrue(legacy.enabled)
        org.junit.Assert.assertEquals(3_601_000L, StandingOrders.nextRunAt(legacy))
        org.junit.Assert.assertTrue(StandingOrderPolicy.due(legacy, 3_601_000L))
    }

    @Test fun acceptsSupportedIntervalsAndRejectsTooFrequentChecks() {
        assertNull(StandingOrderPolicy.validate("brief", StandingOrderPolicy.MIN_MINUTES))
        assertNull(StandingOrderPolicy.validate("brief", StandingOrderPolicy.MAX_MINUTES))
        assertEquals("minimum interval is 15 minutes (saves battery and quota)",
            StandingOrderPolicy.validate("brief", StandingOrderPolicy.MIN_MINUTES - 1))
        assertEquals("maximum interval is one week",
            StandingOrderPolicy.validate("brief", StandingOrderPolicy.MAX_MINUTES + 1))
    }

    @Test fun failedNotificationDoesNotConsumeNewResultDigest() {
        val previous = "old-digest"
        val result = "new information"
        assertEquals(previous, StandingOrderPolicy.digestAfterAttempt(previous, result, true, false))
        assertEquals(StandingOrderPolicy.digest(result),
            StandingOrderPolicy.digestAfterAttempt(previous, result, true, true))
    }

    @Test fun errorsNeverReplaceLastGoodDigest() {
        assertEquals("old-digest",
            StandingOrderPolicy.digestAfterAttempt("old-digest", "error: provider offline", false, false))
        assertEquals("old-digest",
            StandingOrderPolicy.digestAfterAttempt("old-digest", "ERROR: provider offline", false, false))
        assertEquals("old-digest",
            StandingOrderPolicy.digestAfterAttempt("old-digest", "No unread messages", false, false))
    }

    @Test fun digestDistinguishesKnownJavaHashCollisions() {
        // "FB" and "Ea" share String.hashCode(); a persistent alert fingerprint must not.
        assertEquals("2236", "FB".hashCode().toString())
        assertEquals("2236", "Ea".hashCode().toString())
        org.junit.Assert.assertNotEquals(StandingOrderPolicy.digest("FB"), StandingOrderPolicy.digest("Ea"))
    }

    @Test fun emptyAndErrorResultsNeverTriggerAlertsRegardlessOfCase() {
        org.junit.Assert.assertFalse(StandingOrderPolicy.shouldNotify("", "ERROR: provider offline"))
        org.junit.Assert.assertFalse(StandingOrderPolicy.shouldNotify("", "nothing new today"))
        org.junit.Assert.assertFalse(StandingOrderPolicy.shouldNotify("", "Header\n0 matching message(s)."))
    }
}
