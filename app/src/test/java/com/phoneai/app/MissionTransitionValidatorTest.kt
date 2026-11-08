package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 0C Tests: Mission state machine transitions
 */
class MissionTransitionValidatorTest {

    @Test
    fun testValidTransitions() {
        // INTAKE -> PLANNED, CANCELLED
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.INTAKE, OrionMissionStatus.PLANNED))
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.INTAKE, OrionMissionStatus.CANCELLED))

        // PLANNED -> EXECUTING, SUSPENDED, CANCELLED
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.PLANNED, OrionMissionStatus.EXECUTING))
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.PLANNED, OrionMissionStatus.SUSPENDED))
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.PLANNED, OrionMissionStatus.CANCELLED))

        // EXECUTING -> VERIFYING, SUSPENDED, CHECKPOINTED, FAILED, CANCELLED, EXECUTING (replan)
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.EXECUTING, OrionMissionStatus.VERIFYING))
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.EXECUTING, OrionMissionStatus.SUSPENDED))
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.EXECUTING, OrionMissionStatus.CHECKPOINTED))
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.EXECUTING, OrionMissionStatus.FAILED))
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.EXECUTING, OrionMissionStatus.CANCELLED))
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.EXECUTING, OrionMissionStatus.EXECUTING))

        // VERIFYING -> COMPLETED, FAILED, EXECUTING (replan), SUSPENDED
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.VERIFYING, OrionMissionStatus.COMPLETED))
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.VERIFYING, OrionMissionStatus.FAILED))
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.VERIFYING, OrionMissionStatus.EXECUTING))
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.VERIFYING, OrionMissionStatus.SUSPENDED))

        // COMPLETED -> INTAKE (new mission)
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.COMPLETED, OrionMissionStatus.INTAKE))

        // SUSPENDED -> EXECUTING, CANCELLED
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.SUSPENDED, OrionMissionStatus.EXECUTING))
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.SUSPENDED, OrionMissionStatus.CANCELLED))

        // CHECKPOINTED -> EXECUTING, SUSPENDED, CANCELLED
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.CHECKPOINTED, OrionMissionStatus.EXECUTING))
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.CHECKPOINTED, OrionMissionStatus.SUSPENDED))
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.CHECKPOINTED, OrionMissionStatus.CANCELLED))

        // FAILED -> INTAKE (retry), CANCELLED
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.FAILED, OrionMissionStatus.INTAKE))
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.FAILED, OrionMissionStatus.CANCELLED))

        // CANCELLED -> INTAKE
        assertTrue(MissionTransitionValidator.isValid(OrionMissionStatus.CANCELLED, OrionMissionStatus.INTAKE))
    }

    @Test
    fun testInvalidTransitions() {
        // INTAKE cannot go directly to EXECUTING
        assertFalse(MissionTransitionValidator.isValid(OrionMissionStatus.INTAKE, OrionMissionStatus.EXECUTING))
        assertFalse(MissionTransitionValidator.isValid(OrionMissionStatus.INTAKE, OrionMissionStatus.COMPLETED))

        // PLANNED cannot go directly to COMPLETED
        assertFalse(MissionTransitionValidator.isValid(OrionMissionStatus.PLANNED, OrionMissionStatus.COMPLETED))
        assertFalse(MissionTransitionValidator.isValid(OrionMissionStatus.PLANNED, OrionMissionStatus.FAILED))

        // COMPLETED cannot go to EXECUTING or FAILED
        assertFalse(MissionTransitionValidator.isValid(OrionMissionStatus.COMPLETED, OrionMissionStatus.EXECUTING))
        assertFalse(MissionTransitionValidator.isValid(OrionMissionStatus.COMPLETED, OrionMissionStatus.FAILED))

        // CANCELLED cannot go to EXECUTING or COMPLETED
        assertFalse(MissionTransitionValidator.isValid(OrionMissionStatus.CANCELLED, OrionMissionStatus.EXECUTING))
        assertFalse(MissionTransitionValidator.isValid(OrionMissionStatus.CANCELLED, OrionMissionStatus.COMPLETED))

        // FAILED cannot go directly to COMPLETED
        assertFalse(MissionTransitionValidator.isValid(OrionMissionStatus.FAILED, OrionMissionStatus.COMPLETED))
        assertFalse(MissionTransitionValidator.isValid(OrionMissionStatus.FAILED, OrionMissionStatus.EXECUTING))

        // Self-transitions are invalid (no-op)
        assertFalse(MissionTransitionValidator.isValid(OrionMissionStatus.EXECUTING, OrionMissionStatus.EXECUTING)) // except for replanning which is handled separately
    }

    @Test
    fun testTransitionValidationObject() {
        val valid = MissionTransitionValidator.validate(OrionMissionStatus.PLANNED, OrionMissionStatus.EXECUTING)
        assertTrue(valid is MissionTransition.Valid)
        assertEquals(OrionMissionStatus.PLANNED, valid.from)
        assertEquals(OrionMissionStatus.EXECUTING, valid.to)

        val invalid = MissionTransitionValidator.validate(OrionMissionStatus.PLANNED, OrionMissionStatus.COMPLETED)
        assertTrue(invalid is MissionTransition.Invalid)
        assertEquals(OrionMissionStatus.PLANNED, invalid.from)
        assertEquals(OrionMissionStatus.COMPLETED, invalid.to)
        assertNotNull(invalid.reason)
    }
}