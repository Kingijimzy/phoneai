package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGovernancePolicyTest {
    @Test fun generatedMemoriesRequireOwnerReviewBeforePromptUse() {
        assertFalse(MemoryGovernancePolicy.canUseInPrompt("pending"))
        assertFalse(MemoryGovernancePolicy.canUseInPrompt("rejected"))
        assertTrue(MemoryGovernancePolicy.canUseInPrompt("approved"))
    }

    @Test fun sensitiveMemoriesNeverEnterModelContextEvenAfterApproval() {
        assertEquals("sensitive", MemoryGovernancePolicy.sensitivity("my API key is abc123"))
        assertEquals("sensitive", MemoryGovernancePolicy.sensitivity("a normal note", "sensitive"))
        assertFalse(MemoryGovernancePolicy.canUseInPrompt("approved", "sensitive"))
        assertTrue(MemoryGovernancePolicy.canUseInPrompt("approved", "general"))
    }

    @Test fun confidenceIsBoundedAndNonFiniteValuesAreRejected() {
        assertEquals(1.0, MemoryGovernancePolicy.normalizeConfidence(2.0), 0.0)
        assertEquals(0.0, MemoryGovernancePolicy.normalizeConfidence(-1.0), 0.0)
        assertEquals(0.0, MemoryGovernancePolicy.normalizeConfidence(Double.NaN), 0.0)
        assertEquals(0.0, MemoryGovernancePolicy.normalizeConfidence(Double.POSITIVE_INFINITY), 0.0)
    }
}


class OrionSelfHealingPolicyTest {
    @Test fun permissionFailuresAskOwnerInsteadOfRetrying() {
        val signal = OrionSelfHealingPolicy.Signal("phone_control", "SecurityException: permission not granted")
        val diagnosis = OrionSelfHealingPolicy.diagnose(signal)
        assertEquals(OrionSelfHealingPolicy.Domain.PERMISSION, diagnosis.domain)
        assertEquals(OrionSelfHealingPolicy.Recovery.REQUEST_OWNER_PERMISSION, diagnosis.recovery)
        assertFalse(OrionSelfHealingPolicy.mayAutoRecover(diagnosis, signal))
    }

    @Test fun providerFailureUsesOnlyConfiguredFallbackAndIsBounded() {
        val first = OrionSelfHealingPolicy.Signal("provider", "HTTP 429 rate limit", retryCount = 0)
        val diagnosis = OrionSelfHealingPolicy.diagnose(first)
        assertEquals(OrionSelfHealingPolicy.Recovery.USE_CONFIGURED_PROVIDER_FALLBACK, diagnosis.recovery)
        assertTrue(OrionSelfHealingPolicy.mayAutoRecover(diagnosis, first))
        assertEquals(OrionSelfHealingPolicy.Recovery.OWNER_INTERVENTION, OrionSelfHealingPolicy.diagnose(first.copy(retryCount = 1)).recovery)
    }

    @Test fun uncertainWritesAreNeverAutomaticallyRetried() {
        val signal = OrionSelfHealingPolicy.Signal("telegram_send", "timeout after dispatch", operationWasReadOnly = false, sideEffectMayHaveOccurred = true)
        val diagnosis = OrionSelfHealingPolicy.diagnose(signal)
        assertEquals(OrionSelfHealingPolicy.Recovery.RECONCILE_UNCERTAIN_WRITE, diagnosis.recovery)
        assertFalse(OrionSelfHealingPolicy.mayAutoRecover(diagnosis, signal))
    }

    @Test fun interruptedWorkerCanResumeOnlyFromVerifiedCheckpoint() {
        val signal = OrionSelfHealingPolicy.Signal("worker", "process died after checkpoint")
        val diagnosis = OrionSelfHealingPolicy.diagnose(signal)
        assertEquals(OrionSelfHealingPolicy.Recovery.RESUME_VERIFIED_CHECKPOINT, diagnosis.recovery)
        assertTrue(OrionSelfHealingPolicy.mayAutoRecover(diagnosis, signal))
    }

    @Test fun extensionMustPassEvidenceTestsAndOwnerApprovalBeforeActivation() {
        val base = OrionSelfHealingPolicy.ExtensionEvidence(true, true, "MIT", true, 2, 2, false, false, false)
        assertEquals(OrionSelfHealingPolicy.ExtensionDecision.READY_FOR_OWNER_REVIEW, OrionSelfHealingPolicy.extensionDecision(base))
        assertEquals(OrionSelfHealingPolicy.ExtensionDecision.ENABLE, OrionSelfHealingPolicy.extensionDecision(base.copy(ownerApproved = true)))
        assertEquals(OrionSelfHealingPolicy.ExtensionDecision.DRAFT_ONLY, OrionSelfHealingPolicy.extensionDecision(base.copy(licenseId = "unknown")))
        assertEquals(OrionSelfHealingPolicy.ExtensionDecision.REJECT, OrionSelfHealingPolicy.extensionDecision(base.copy(modifiesPolicyOrPermissions = true)))
        assertEquals(OrionSelfHealingPolicy.ExtensionDecision.DRAFT_ONLY, OrionSelfHealingPolicy.extensionDecision(base.copy(passedTests = 1)))
    }

    @Test fun cycleBudgetStopsRunawaySelfRepair() {
        assertTrue(OrionSelfHealingPolicy.mayStartAnotherCycle(0, 3))
        assertTrue(OrionSelfHealingPolicy.mayStartAnotherCycle(2, 3))
        assertFalse(OrionSelfHealingPolicy.mayStartAnotherCycle(3, 3))
        assertFalse(OrionSelfHealingPolicy.mayStartAnotherCycle(-1, 3))
    }
}
