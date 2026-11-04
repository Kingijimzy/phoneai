package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class AutonomyGoalPolicyTest {
    private fun goal(every: Int = 60, last: Long = 0L, enabled: Boolean = true) =
        AutonomyGoal("g1", "Summarise my unread email", every, last, "", enabled)

    @Test fun validationRejectsTooShortAndTooFrequentGoals() {
        assertNotNull(AutonomyGoalPolicy.validate("hi", 60))
        assertNotNull(AutonomyGoalPolicy.validate("Summarise my unread email", 5))
        assertNull(AutonomyGoalPolicy.validate("Summarise my unread email", 60))
    }

    @Test fun validationRejectsTooLongPromptsAndIntervals() {
        assertNotNull(AutonomyGoalPolicy.validate("x".repeat(501), 60))
        assertNotNull(AutonomyGoalPolicy.validate("Summarise my unread email", 8 * 24 * 60))
    }

    @Test fun aNeverRunGoalIsDueImmediately() {
        assertTrue(AutonomyGoalPolicy.due(goal(last = 0L), 1_000L))
    }

    @Test fun aGoalIsNotDueBeforeItsInterval() {
        val now = 10_000_000L
        assertFalse(AutonomyGoalPolicy.due(goal(every = 60, last = now - 30 * 60_000L), now))
        assertTrue(AutonomyGoalPolicy.due(goal(every = 60, last = now - 61 * 60_000L), now))
    }

    @Test fun aDisabledGoalIsNeverDue() {
        assertFalse(AutonomyGoalPolicy.due(goal(last = 0L, enabled = false), 1_000L))
    }
}
