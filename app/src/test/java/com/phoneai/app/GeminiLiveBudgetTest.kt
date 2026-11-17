package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveBudgetTest {
    @Test fun remainingNeverGoesNegative() {
        assertEquals(0L, GeminiLiveBudget.remainingMs(40 * 60_000L, 30))
        assertEquals(10 * 60_000L, GeminiLiveBudget.remainingMs(20 * 60_000L, 30))
    }
    @Test fun sessionIsShortenedToWhatIsLeft() {
        assertEquals(5 * 60_000L, GeminiLiveBudget.sessionLimitMs(25 * 60_000L, 30))
        assertEquals(GeminiLivePolicy.SESSION_LIMIT_MS, GeminiLiveBudget.sessionLimitMs(0L, 600))
    }
    @Test fun labelRoundsUp() { assertTrue(GeminiLiveBudget.label(61_000L).startsWith("2 min")) }
}
