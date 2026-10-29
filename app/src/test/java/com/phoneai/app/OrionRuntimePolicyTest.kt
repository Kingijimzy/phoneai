package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionRuntimePolicyTest {
    @Test fun priorityIsClampedAndWorkTypesAreExplicit() {
        assertEquals(
            listOf("LEARN_FROM_RESULT", "CAPABILITY_RECHECK", "RESTORE_PENDING", "SOUL_WATCH"),
            OrionRuntime.WorkType.values().map { it.name }
        )
        assertEquals(-10, (-100).coerceIn(-10, 10))
        assertEquals(10, 100.coerceIn(-10, 10))
    }

    @Test fun retryScheduleUsesBoundedExponentialBackoff() {
        assertEquals(0L, OrionRuntimePolicy.retryDelayMs(0))
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L),
            (1..4).map(OrionRuntimePolicy::retryDelayMs))
        assertEquals(OrionRuntimePolicy.MAX_BACKOFF_MS, OrionRuntimePolicy.retryDelayMs(99))
    }

    @Test fun retryStopsOnlyAfterConfiguredAttemptLimit() {
        assertFalse(OrionRuntimePolicy.shouldRetry(0))
        assertTrue(OrionRuntimePolicy.shouldRetry(1))
        assertTrue(OrionRuntimePolicy.shouldRetry(2))
        assertTrue(OrionRuntimePolicy.shouldRetry(3))
        assertFalse(OrionRuntimePolicy.shouldRetry(OrionRuntimePolicy.MAX_ATTEMPTS))
    }

    @Test fun newWorkRecoversDegradedStateButDoesNotResumePausedOrStoppedService() {
        assertEquals(OrionRuntime.State.ACTIVE,
            OrionRuntimePolicy.stateAfterEnqueue(OrionRuntime.State.DEGRADED, true))
        assertEquals(OrionRuntime.State.PAUSED,
            OrionRuntimePolicy.stateAfterEnqueue(OrionRuntime.State.PAUSED, true))
        assertEquals(OrionRuntime.State.STOPPED,
            OrionRuntimePolicy.stateAfterEnqueue(OrionRuntime.State.STOPPED, true))
        assertEquals(OrionRuntime.State.DEGRADED,
            OrionRuntimePolicy.stateAfterEnqueue(OrionRuntime.State.DEGRADED, false))
    }

    @Test fun retryCheckpointPreservesGoalAndProgressAndSanitizesError() {
        val original = OrionRuntime.WorkItem(
            id = "task-1",
            key = "deploy-release",
            type = OrionRuntime.WorkType.RESTORE_PENDING,
            priority = 8,
            createdAt = 100L,
            attempts = 0,
            goal = "Build and verify the release",
            checkpoint = "unit tests passed"
        )
        val retry = OrionRuntimePolicy.retryItem(
            original, now = 1_000L,
            error = "network\\n timeout while checking result",
            checkpoint = "APK validation pending"
        )
        assertEquals("Build and verify the release", retry?.goal)
        assertEquals("APK validation pending", retry?.checkpoint)
        assertEquals("network timeout while checking result", retry?.lastError)
        assertEquals(1, retry?.attempts)
        assertEquals(31_000L, retry?.notBefore)
        assertNull(OrionRuntimePolicy.retryItem(original.copy(attempts = OrionRuntimePolicy.MAX_ATTEMPTS - 1),
            now = 1_000L, error = "permanent failure"))
    }

    @Test fun nextEligibleDelayUsesEarliestQueuedDeadline() {
        assertEquals(0L, OrionRuntimePolicy.nextEligibleDelayMs(1_000L, listOf(900L, 5_000L)))
        assertEquals(2_000L, OrionRuntimePolicy.nextEligibleDelayMs(1_000L, listOf(3_000L, 7_000L)))
        assertNull(OrionRuntimePolicy.nextEligibleDelayMs(1_000L, emptyList()))
    }
}
