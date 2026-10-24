package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestBudgetGuardTest {
    @Test fun requestBudgetBlocksBeforeTheNextNetworkAttempt() {
        val guard = RequestBudgetGuard(3)
        assertTrue(guard.reserveRequest())
        assertTrue(guard.reserveRequest())
        assertTrue(guard.reserveRequest())
        assertEquals(3, guard.requestCount())
        assertFalse("Fourth attempt must be blocked before networking", guard.reserveRequest())
        assertEquals(3, guard.requestCount())
    }

    @Test fun identicalFailureStopsAtThreeButDifferentSignatureHasOwnCount() {
        val guard = RequestBudgetGuard()
        assertEquals(1, guard.recordFailure("provider|url|401|body-hash"))
        assertEquals(2, guard.recordFailure("provider|url|401|body-hash"))
        assertEquals(3, guard.recordFailure("provider|url|401|body-hash"))
        assertEquals(1, guard.recordFailure("provider|different-url|401|body-hash"))
    }

    @Test fun newCommandResetsBudgetAndFailureCounter() {
        val guard = RequestBudgetGuard(1)
        assertTrue(guard.reserveRequest())
        assertFalse(guard.reserveRequest())
        assertEquals(1, guard.recordFailure("same"))
        guard.beginCommand()
        assertTrue(guard.reserveRequest())
        assertEquals(1, guard.recordFailure("same"))
    }
}
