package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StandingOrderPolicyTest {
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
    }
}
