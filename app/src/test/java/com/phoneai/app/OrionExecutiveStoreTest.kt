package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionExecutiveStoreTest {
    @Test fun executivePhasesRequireVerificationBeforeLearningOrCompletion() {
        assertTrue(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.GROUND, OrionExecutiveStore.Phase.RETRIEVE))
        assertTrue(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.PERFORM, OrionExecutiveStore.Phase.VERIFY))
        assertTrue(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.VERIFY, OrionExecutiveStore.Phase.LEARN))
        assertTrue(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.LEARN, OrionExecutiveStore.Phase.COMPLETED))
        assertFalse(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.PERFORM, OrionExecutiveStore.Phase.COMPLETED))
        assertFalse(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.GROUND, OrionExecutiveStore.Phase.COMPLETED))
    }

    @Test fun blockedAndOwnerWaitingObjectivesHaveExplicitRecoveryPaths() {
        assertTrue(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.ASSESS, OrionExecutiveStore.Phase.WAITING_FOR_OWNER))
        assertTrue(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.WAITING_FOR_OWNER, OrionExecutiveStore.Phase.GROUND))
        assertTrue(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.PERFORM, OrionExecutiveStore.Phase.BLOCKED))
        assertTrue(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.BLOCKED, OrionExecutiveStore.Phase.GROUND))
        assertFalse(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.COMPLETED, OrionExecutiveStore.Phase.GROUND))
        assertFalse(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.FAILED, OrionExecutiveStore.Phase.PERFORM))
    }
    private fun step(id: String, dependencies: List<String> = emptyList()) =
        OrionExecutiveStore.Step(
            id = id, objectiveId = "objective", ordinal = 0, title = id,
            dependencies = dependencies, status = OrionExecutiveStore.StepStatus.PENDING,
            capability = "", argumentsDigest = "", attempts = 0, resultSummary = "", evidenceRef = ""
        )

    @Test fun plansRejectDuplicateIdsMissingDependenciesAndCycles() {
        assertFalse(OrionExecutiveStore.isValidPlan(listOf(step("a"), step("a"))))
        assertFalse(OrionExecutiveStore.isValidPlan(listOf(step("a", listOf("missing")))))
        assertFalse(OrionExecutiveStore.isValidPlan(listOf(step("a", listOf("b")), step("b", listOf("a")))))
        assertFalse(OrionExecutiveStore.isValidPlan(listOf(step("a", listOf("a")))))
    }

    @Test fun plansAcceptBoundedAcyclicDependencyGraphs() {
        assertTrue(OrionExecutiveStore.isValidPlan(listOf(step("research"), step("verify", listOf("research")))))
        assertFalse(OrionExecutiveStore.isValidPlan(emptyList()))
        assertFalse(OrionExecutiveStore.isValidPlan((0..32).map { step("step-$it") }))
    }

    @Test fun plansRejectDuplicateDependenciesAndCrossObjectiveSteps() {
        assertFalse(OrionExecutiveStore.isValidPlan(listOf(step("a"), step("b", listOf("a", "a")))))
        val foreign = step("b").copy(objectiveId = "another-objective")
        assertFalse(OrionExecutiveStore.isValidPlan(listOf(step("a"), foreign)))
    }

    @Test fun replanningPreservesEvidenceOnlyWhenStepMeaningIsIdentical() {
        val original = step("run-tests").copy(
            title = "Run unit tests",
            capability = "gradle_test",
            argumentsDigest = "digest-1",
            status = OrionExecutiveStore.StepStatus.VERIFIED,
            attempts = 1,
            resultSummary = "All tests passed",
            evidenceRef = "evidence-123"
        )
        assertTrue(OrionExecutiveStore.canPreserveStepState(original, original.copy(ordinal = 4)))
        assertFalse(OrionExecutiveStore.canPreserveStepState(original, original.copy(title = "Run different tests")))
        assertFalse(OrionExecutiveStore.canPreserveStepState(original, original.copy(dependencies = listOf("prepare"))))
        assertFalse(OrionExecutiveStore.canPreserveStepState(original, original.copy(capability = "shell")))
        assertFalse(OrionExecutiveStore.canPreserveStepState(original, original.copy(argumentsDigest = "digest-2")))
    }

    @Test fun interruptedActionsRequireReconciliationBeforeTheyCanBeResolved() {
        assertFalse(OrionExecutiveStore.canResolveStepResult(
            OrionExecutiveStore.StepStatus.PENDING, OrionExecutiveStore.StepStatus.VERIFIED, "receipt"
        ))
        assertFalse(OrionExecutiveStore.canResolveStepResult(
            OrionExecutiveStore.StepStatus.RECONCILIATION_REQUIRED, OrionExecutiveStore.StepStatus.VERIFIED, ""
        ))
        assertTrue(OrionExecutiveStore.canResolveStepResult(
            OrionExecutiveStore.StepStatus.RECONCILIATION_REQUIRED, OrionExecutiveStore.StepStatus.VERIFIED, "observed-state"
        ))
        assertTrue(OrionExecutiveStore.canResolveStepResult(
            OrionExecutiveStore.StepStatus.RECONCILIATION_REQUIRED, OrionExecutiveStore.StepStatus.FAILED, ""
        ))
    }


    @Test fun eachToolDispatchRunsTheExecutiveLifecycleBeforeExecution() {
        assertEquals(
            listOf(
                OrionExecutiveStore.Phase.RETRIEVE,
                OrionExecutiveStore.Phase.ASSESS,
                OrionExecutiveStore.Phase.SELECT,
                OrionExecutiveStore.Phase.PERFORM
            ),
            OrionExecutiveStore.dispatchStartPath()
        )
    }

    @Test fun onlyVerifiedDispatchesCanCompleteTheExecutiveLifecycle() {
        assertEquals(
            listOf(
                OrionExecutiveStore.Phase.VERIFY,
                OrionExecutiveStore.Phase.LEARN,
                OrionExecutiveStore.Phase.COMPLETED
            ),
            OrionExecutiveStore.dispatchFinishPath(OrionExecutiveStore.DispatchOutcome.VERIFIED)
        )
        assertEquals(
            listOf(OrionExecutiveStore.Phase.VERIFY, OrionExecutiveStore.Phase.BLOCKED),
            OrionExecutiveStore.dispatchFinishPath(OrionExecutiveStore.DispatchOutcome.EXECUTED_UNVERIFIED)
        )
        assertEquals(
            listOf(OrionExecutiveStore.Phase.BLOCKED),
            OrionExecutiveStore.dispatchFinishPath(OrionExecutiveStore.DispatchOutcome.FAILED)
        )
        assertEquals(
            listOf(OrionExecutiveStore.Phase.BLOCKED),
            OrionExecutiveStore.dispatchFinishPath(OrionExecutiveStore.DispatchOutcome.RECONCILIATION_REQUIRED)
        )
    }

}
