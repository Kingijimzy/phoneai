package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRequestBudgetTest {
    @Test fun allowsExactlyConfiguredRequestCount() {
        val budget = CommandRequestBudget(15)
        repeat(15) { assertTrue("Request ${it + 1} should be reserved", budget.tryReserve()) }
        assertEquals(15, budget.used())
        assertFalse("The 16th request must be blocked", budget.tryReserve())
        assertEquals(15, budget.used())
    }

    @Test fun resetStartsANewCommandAndClearsFailureCounts() {
        val budget = CommandRequestBudget(2)
        assertTrue(budget.tryReserve())
        assertEquals(1, budget.noteFailure("provider|HTTP 500"))
        assertEquals(2, budget.noteFailure("provider|HTTP 500"))

        budget.reset()

        assertEquals(0, budget.used())
        assertTrue(budget.tryReserve())
        assertEquals("Failure counter must reset for the new command", 1, budget.noteFailure("provider|HTTP 500"))
    }

    @Test fun distinctFailureSignaturesAreCountedSeparately() {
        val budget = CommandRequestBudget()
        assertEquals(1, budget.noteFailure("provider|HTTP 500|model-a"))
        assertEquals(1, budget.noteFailure("provider|HTTP 404|model-b"))
        assertEquals(2, budget.noteFailure("provider|HTTP 500|model-a"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveBudget() {
        CommandRequestBudget(0)
    }
}
