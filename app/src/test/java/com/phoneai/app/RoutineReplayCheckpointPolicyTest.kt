package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class RoutineReplayCheckpointPolicyTest {
    private fun snapshot(
        routine: String = "daily rewards",
        next: Int = 2,
        inFlight: Int = -1,
        total: Int = 5
    ) = RoutineReplayCheckpointStore.Snapshot(routine, next, inFlight, total, 100L)

    @Test fun checkpointRejectsInvalidCursorRangesAndMismatchedInFlightStep() {
        assertTrue(RoutineReplayCheckpointPolicy.isValid("daily rewards", 5, 2, -1))
        assertTrue(RoutineReplayCheckpointPolicy.isValid("daily rewards", 5, 2, 2))
        assertFalse(RoutineReplayCheckpointPolicy.isValid("", 5, 2, -1))
        assertFalse(RoutineReplayCheckpointPolicy.isValid("daily rewards", 0, 0, -1))
        assertFalse(RoutineReplayCheckpointPolicy.isValid("daily rewards", 101, 0, -1))
        assertFalse(RoutineReplayCheckpointPolicy.isValid("daily rewards", 5, 6, -1))
        assertFalse(RoutineReplayCheckpointPolicy.isValid("daily rewards", 5, 2, 3))
    }

    @Test fun onlyOneValidRoutineCanOwnTheCheckpoint() {
        assertTrue(RoutineReplayCheckpointPolicy.canBegin(false, "daily rewards", 5))
        assertFalse(RoutineReplayCheckpointPolicy.canBegin(true, "other routine", 5))
        assertFalse(RoutineReplayCheckpointPolicy.canBegin(false, " ", 5))
        assertFalse(RoutineReplayCheckpointPolicy.canBegin(false, "daily rewards", 101))
    }

    @Test fun inFlightMarkerCannotOverwriteAnotherUnresolvedStep() {
        assertTrue(RoutineReplayCheckpointPolicy.canMarkInFlight(snapshot(next = 2), "daily rewards", 5, 2))
        assertFalse(RoutineReplayCheckpointPolicy.canMarkInFlight(snapshot(next = 2, inFlight = 2), "daily rewards", 5, 2))
        assertFalse(RoutineReplayCheckpointPolicy.canMarkInFlight(snapshot(next = 1), "daily rewards", 5, 2))
        assertFalse(RoutineReplayCheckpointPolicy.canMarkInFlight(snapshot(), "other routine", 5, 2))
    }

    @Test fun resultMustMatchTheRecordedInFlightStep() {
        assertTrue(RoutineReplayCheckpointPolicy.canMarkCompleted(snapshot(next = 2, inFlight = 2), "daily rewards", 2))
        assertFalse(RoutineReplayCheckpointPolicy.canMarkCompleted(snapshot(next = 2, inFlight = -1), "daily rewards", 2))
        assertFalse(RoutineReplayCheckpointPolicy.canMarkCompleted(snapshot(next = 2, inFlight = 2), "other routine", 2))
        assertFalse(RoutineReplayCheckpointPolicy.canMarkCompleted(snapshot(next = 2, inFlight = 2), "daily rewards", 1))
    }

    @Test fun pauseOnlyAcceptsCursorWithinRoutineBounds() {
        assertTrue(RoutineReplayCheckpointPolicy.canPause(snapshot(next = 2), "daily rewards", 0))
        assertTrue(RoutineReplayCheckpointPolicy.canPause(snapshot(next = 2), "daily rewards", 5))
        assertFalse(RoutineReplayCheckpointPolicy.canPause(snapshot(), "other routine", 2))
        assertFalse(RoutineReplayCheckpointPolicy.canPause(snapshot(), "daily rewards", 6))
    }
}
