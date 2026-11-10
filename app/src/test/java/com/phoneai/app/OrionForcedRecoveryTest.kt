package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionForcedRecoveryTest {
    @Test fun recoveryForSameFailedCapabilityDoesNotDependOnGoalKeywords() {
        val pythonA = OrionForcedRecovery.afterFailure("scrape APKVision", "python_exec", "error: timeout", 0)
        val pythonB = OrionForcedRecovery.afterFailure("check a spreadsheet", "python_exec", "error: timeout", 0)
        assertEquals(pythonA, pythonB)
        assertTrue(pythonA.shouldRetry)
        assertTrue(pythonA.nextToolHint!!.contains("Python runtime"))
    }

    @Test fun browserRecoveryIsCapabilityBasedRatherThanWebsiteKeywordBased() {
        val a = OrionForcedRecovery.afterFailure("download an app", "browser_navigate", "error: navigation failed", 0)
        val b = OrionForcedRecovery.afterFailure("read a public article", "browser_navigate", "error: navigation failed", 0)
        assertEquals(a, b)
        assertTrue(a.nextToolHint!!.contains("browser state"))
    }

    @Test fun failedSportsCapabilitySwitchesToAlternateResearchSource() {
        val plan = OrionForcedRecovery.afterFailure("atletico vs barca scoreline", "live_sports", "error: unavailable", 0)
        assertTrue(plan.shouldRetry)
        assertTrue(plan.nextToolHint!!.contains("web", ignoreCase = true))
    }

    @Test fun retryBudgetStopsAfterTwoAlternativeAttempts() {
        val plan = OrionForcedRecovery.afterFailure("any goal", "shell", "exit=1", 2)
        assertFalse(plan.shouldRetry)
        assertNull(plan.nextToolHint)
        assertNotNull(plan.userMessageIfGiveUp)
    }
}
