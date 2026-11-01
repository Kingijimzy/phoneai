package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionSelfHealingPolicyTest {
    @Test fun reversibleTestedIdempotentRepairCanUseOnlyTheExplicitAutoApplyPath() {
        val proposal = OrionRepairProposal(
            id = "repair-1",
            findingId = "finding-1",
            summary = "Restore a reversible local cache entry",
            risk = OrionRepairRisk.REVERSIBLE,
            isIdempotent = true,
            hasRollback = true,
            requiredOwnerApproval = false,
            status = OrionRepairStatus.TEST_PASSED,
            testRunId = "test-1",
            testPassed = true
        )
        assertTrue(OrionSelfHealingPolicy.mayAutoApply(proposal))
        assertEquals(
            OrionRepairStatus.APPLIED,
            OrionSelfHealingPolicy.nextRepairStatus(
                current = OrionRepairStatus.TEST_PASSED,
                testsPassed = true,
                ownerApproved = false,
                applied = true,
                verificationPassed = false,
                ownerApprovalRequired = false
            )
        )
        assertEquals(
            OrionRepairStatus.VERIFIED,
            OrionSelfHealingPolicy.nextRepairStatus(
                OrionRepairStatus.APPLIED, true, false, true, true
            )
        )
    }

    @Test fun consequentialRepairWaitsForOwnerAndRequiresAppliedReceipt() {
        assertFalse(OrionSelfHealingPolicy.mayAutoApply(
            OrionRepairProposal(
                "repair-2", "finding-2", "Change an external account setting",
                OrionRepairRisk.EXTERNAL_SIDE_EFFECT, true, true, true,
                OrionRepairStatus.TEST_PASSED, "test-2", true
            )
        ))
        assertEquals(
            OrionRepairStatus.AWAITING_OWNER,
            OrionSelfHealingPolicy.nextRepairStatus(
                OrionRepairStatus.TEST_PASSED, true, false, false, false
            )
        )
        assertNull(OrionSelfHealingPolicy.nextRepairStatus(
            OrionRepairStatus.AWAITING_OWNER, true, true, false, false
        ))
        assertEquals(
            OrionRepairStatus.APPLIED,
            OrionSelfHealingPolicy.nextRepairStatus(
                OrionRepairStatus.AWAITING_OWNER, true, true, true, false
            )
        )
    }

    @Test fun failedPostApplyVerificationRequiresRollback() {
        assertEquals(
            OrionRepairStatus.ROLLED_BACK,
            OrionSelfHealingPolicy.nextRepairStatus(
                OrionRepairStatus.APPLIED, true, true, true, false
            )
        )
        assertNull(OrionSelfHealingPolicy.nextRepairStatus(
            OrionRepairStatus.ROLLED_BACK, true, true, true, true
        ))
    }

    @Test fun autoRetryIsSingleAttemptAndRestrictedToReversibleWork() {
        assertTrue(OrionSelfHealingPolicy.mayAutoRetry(
            OrionRepairRisk.REVERSIBLE, true, true, 0, OrionFindingSeverity.WARNING
        ))
        assertFalse(OrionSelfHealingPolicy.mayAutoRetry(
            OrionRepairRisk.REVERSIBLE, true, true, 1, OrionFindingSeverity.WARNING
        ))
        assertFalse(OrionSelfHealingPolicy.mayAutoRetry(
            OrionRepairRisk.SECURITY_SENSITIVE, true, true, 0, OrionFindingSeverity.WARNING
        ))
        assertFalse(OrionSelfHealingPolicy.mayAutoRetry(
            OrionRepairRisk.REVERSIBLE, true, true, 0, OrionFindingSeverity.CRITICAL
        ))
    }
}
