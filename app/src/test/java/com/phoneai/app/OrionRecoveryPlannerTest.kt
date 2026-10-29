package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionRecoveryPlannerTest {
    @Test fun unverifiedActionInspectsStateBeforeAnyRetry() {
        val guidance = OrionRecoveryPlanner.forFailure("click", "error: verification failed: no visible screen change")
        assertEquals(OrionRecoveryPlanner.Strategy.INSPECT_STATE, guidance.strategy)
        assertTrue(guidance.mustNotRepeatSameCall)
    }

    @Test fun deniedApprovalStopsInsteadOfReprompting() {
        val guidance = OrionRecoveryPlanner.forFailure("sms", "error: user did not approve this tool action")
        assertEquals(OrionRecoveryPlanner.Strategy.STOP_AND_REPORT, guidance.strategy)
    }

    @Test fun missingPermissionRequestsSetupAndRediscovery() {
        val guidance = OrionRecoveryPlanner.forFailure("read_notifications", "error: notification permission is missing")
        assertEquals(OrionRecoveryPlanner.Strategy.REQUEST_PERMISSION_OR_SETUP, guidance.strategy)
        assertTrue(guidance.promptLine().contains("rediscover capability state"))
    }

    @Test fun safetyBlockNeverSuggestsBypass() {
        val guidance = OrionRecoveryPlanner.forFailure("shell", "error: blocked by safety policy")
        assertEquals(OrionRecoveryPlanner.Strategy.STOP_AND_REPORT, guidance.strategy)
        assertTrue(guidance.reason.contains("bypass"))
    }

    @Test fun transientFailureChoosesAnotherMethodWithoutRapidRetry() {
        val guidance = OrionRecoveryPlanner.forFailure("browser_navigate", "error: request timeout")
        assertEquals(OrionRecoveryPlanner.Strategy.CHOOSE_DIFFERENT_METHOD, guidance.strategy)
        assertTrue(guidance.reason.contains("retry within the shared request budget"))
        assertTrue(guidance.mustNotRepeatSameCall)
    }

    @Test fun staleTargetUsesFreshEvidenceAndDifferentArguments() {
        val guidance = OrionRecoveryPlanner.forFailure("click", "error: target not found")
        assertEquals(OrionRecoveryPlanner.Strategy.CHOOSE_DIFFERENT_METHOD, guidance.strategy)
        assertTrue(guidance.reason.contains("stale arguments"))
    }
}
