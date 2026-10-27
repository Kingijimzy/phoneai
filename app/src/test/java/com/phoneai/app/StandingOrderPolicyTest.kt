package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandingOrderPolicyTest {
    @Test fun standingOrderIntervalsAreBoundedToSaveBatteryAndQuota() {
        assertNull(StandingOrderPolicy.validate("brief", StandingOrderPolicy.MIN_MINUTES))
        assertNull(StandingOrderPolicy.validate("brief", StandingOrderPolicy.MAX_MINUTES))
        assertTrue(StandingOrderPolicy.validate("brief", StandingOrderPolicy.MIN_MINUTES - 1) != null)
        assertTrue(StandingOrderPolicy.validate("brief", StandingOrderPolicy.MAX_MINUTES + 1) != null)
        assertTrue(StandingOrderPolicy.validate("unknown", 60) != null)
    }

    @Test fun eachOrderUsesItsOwnPersistedInterval() {
        val order = StandingOrder("brief", 30, 1_000L, "")
        assertFalse(StandingOrderPolicy.due(order, 1_000L + 29 * 60_000L))
        assertTrue(StandingOrderPolicy.due(order, 1_000L + 30 * 60_000L))
    }

    @Test fun watcherNotifiesOnlyForChangedUsefulResults() {
        val prior = StandingOrderPolicy.digest("new message")
        assertFalse(StandingOrderPolicy.shouldNotify(prior, "new message"))
        assertTrue(StandingOrderPolicy.shouldNotify(prior, "different message"))
        assertFalse(StandingOrderPolicy.shouldNotify(prior, "error: provider unavailable"))
        assertFalse(StandingOrderPolicy.shouldNotify(prior, "No unread messages"))
        assertFalse(StandingOrderPolicy.shouldNotify(prior, "Nothing new"))
    }

    @Test fun firstUsefulResultIsNotifiedButEmptyResultIsNot() {
        assertTrue(StandingOrderPolicy.shouldNotify("", "new GitHub notification"))
        assertFalse(StandingOrderPolicy.shouldNotify("", "No unread messages"))
        assertNull(StandingOrderPolicy.validate("github_notifications", 60))
    }

    @Test fun failedNotificationDoesNotConsumeTheNewResultDigest() {
        val previous = "old-digest"
        val newResult = "new GitHub notification"
        assertEquals(previous, StandingOrderPolicy.digestAfterAttempt(previous, newResult, true, false))
        assertEquals(StandingOrderPolicy.digest(newResult),
            StandingOrderPolicy.digestAfterAttempt(previous, newResult, true, true))
        assertEquals(previous,
            StandingOrderPolicy.digestAfterAttempt(previous, "error: network unavailable", false, false))
    }
}
