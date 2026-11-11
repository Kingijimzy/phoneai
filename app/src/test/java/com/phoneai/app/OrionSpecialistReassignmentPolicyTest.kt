package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionSpecialistReassignmentPolicyTest {
    @Test fun acceptsDifferentRegisteredBackgroundSpecialistForQueuedJob() {
        assertNull(
            OrionSpecialistReassignmentPolicy.rejection(
                currentAgentId = "research",
                targetAgentId = "coder",
                status = OrionWorkerJobStore.Status.QUEUED
            )
        )
    }

    @Test fun rejectsSameSpecialistAndUnknownOrCustomTargets() {
        assertNotNull(
            OrionSpecialistReassignmentPolicy.rejection(
                "research", "research", OrionWorkerJobStore.Status.RUNNING
            )
        )
        assertNotNull(
            OrionSpecialistReassignmentPolicy.rejection(
                "research", "custom_helper", OrionWorkerJobStore.Status.QUEUED
            )
        )
        assertNotNull(
            OrionSpecialistReassignmentPolicy.rejection(
                "research", "not_registered", OrionWorkerJobStore.Status.QUEUED
            )
        )
    }

    @Test fun refusesReassignmentAfterTerminalOutcome() {
        listOf(
            OrionWorkerJobStore.Status.COMPLETED,
            OrionWorkerJobStore.Status.FAILED,
            OrionWorkerJobStore.Status.CANCELLED,
            OrionWorkerJobStore.Status.NEEDS_REVIEW
        ).forEach { status ->
            assertNotNull(
                OrionSpecialistReassignmentPolicy.rejection("research", "coder", status)
            )
        }
    }
}
