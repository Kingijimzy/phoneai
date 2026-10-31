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
    private fun completeDraft(status: OrionExtensionStatus = OrionExtensionStatus.AWAITING_OWNER) =
        OrionExtensionDraft(
            id = "calendar-helper", name = "Calendar helper", purpose = "Parse a calendar reminder",
            sourceUrls = listOf("https://example.org/source"), licenseId = "MIT",
            licenseEvidence = "Repository LICENSE file reviewed",
            declaredCapabilities = setOf("calendar.read"), sandboxTestRunId = "test-run-1",
            sandboxTestsPassed = true, rollbackPlan = "Disable the extension and restore prior manifest",
            status = status
        )

    @Test fun autoRetryRequiresReversibleIdempotentAndRollback() {
        assertTrue(OrionSelfHealingPolicy.mayAutoRetry(OrionRepairRisk.REVERSIBLE, true, true, 0, OrionFindingSeverity.ERROR))
        assertFalse(OrionSelfHealingPolicy.mayAutoRetry(OrionRepairRisk.REVERSIBLE, true, true, 1, OrionFindingSeverity.ERROR))
        assertFalse(OrionSelfHealingPolicy.mayAutoRetry(OrionRepairRisk.DATA_AFFECTING, true, true, 0, OrionFindingSeverity.ERROR))
        assertFalse(OrionSelfHealingPolicy.mayAutoRetry(OrionRepairRisk.REVERSIBLE, false, true, 0, OrionFindingSeverity.ERROR))
        assertFalse(OrionSelfHealingPolicy.mayAutoRetry(OrionRepairRisk.REVERSIBLE, true, false, 0, OrionFindingSeverity.ERROR))
        assertFalse(OrionSelfHealingPolicy.mayAutoRetry(OrionRepairRisk.REVERSIBLE, true, true, 0, OrionFindingSeverity.CRITICAL))
    }

    @Test fun modelGeneratedRepairCannotAutoApplyWithoutTestEvidence() {
        val proposal = OrionRepairProposal(
            id = "repair-1", findingId = "finding-1", summary = "Fix a null guard",
            risk = OrionRepairRisk.REVERSIBLE, isIdempotent = true, hasRollback = true,
            requiredOwnerApproval = false, status = OrionRepairStatus.TEST_PASSED,
            testRunId = "ci-123", testPassed = true
        )
        assertTrue(OrionSelfHealingPolicy.mayAutoApply(proposal))
        assertFalse(OrionSelfHealingPolicy.mayAutoApply(proposal.copy(testPassed = false)))
        assertFalse(OrionSelfHealingPolicy.mayAutoApply(proposal.copy(requiredOwnerApproval = true)))
        assertFalse(OrionSelfHealingPolicy.mayAutoApply(proposal.copy(risk = OrionRepairRisk.SECURITY_SENSITIVE)))
    }

    @Test fun failedTestsCannotAdvanceRepairToApplied() {
        assertEquals(OrionRepairStatus.TEST_FAILED,
            OrionSelfHealingPolicy.nextRepairStatus(OrionRepairStatus.TESTING, false, false, false, false))
        assertEquals(null,
            OrionSelfHealingPolicy.nextRepairStatus(OrionRepairStatus.TEST_FAILED, true, true, true, true))
        assertEquals(OrionRepairStatus.VERIFIED,
            OrionSelfHealingPolicy.nextRepairStatus(OrionRepairStatus.APPLIED, true, true, true, true))
        assertEquals(OrionRepairStatus.ROLLED_BACK,
            OrionSelfHealingPolicy.nextRepairStatus(OrionRepairStatus.APPLIED, true, true, true, false))
    }

    @Test fun extensionDraftRequiresSourceLicenseTestsAndRollbackEvidence() {
        val valid = completeDraft()
        assertTrue(OrionSelfHealingPolicy.validateExtension(valid).isEmpty())
        assertTrue(OrionSelfHealingPolicy.validateExtension(valid.copy(sourceUrls = listOf("http://insecure.example"))).isNotEmpty())
        assertTrue(OrionSelfHealingPolicy.validateExtension(valid.copy(licenseId = null)).isNotEmpty())
        assertTrue(OrionSelfHealingPolicy.validateExtension(valid.copy(sandboxTestRunId = null)).isNotEmpty())
        assertTrue(OrionSelfHealingPolicy.validateExtension(valid.copy(rollbackPlan = "")).isNotEmpty())
    }

    @Test fun extensionActivationRequiresOwnerApprovalAndReviewState() {
        val awaiting = completeDraft()
        assertFalse(OrionSelfHealingPolicy.canActivateExtension(awaiting, false))
        assertTrue(OrionSelfHealingPolicy.canActivateExtension(awaiting, true))
        assertFalse(OrionSelfHealingPolicy.canActivateExtension(completeDraft(OrionExtensionStatus.TESTED), true))
    }

    @Test fun diagnosticReportSeparatesWarningsFromStructuralErrors() {
        val warning = OrionDiagnosticFinding(
            id = "finding-1", component = "background_runtime", title = "Queue waiting",
            detail = "Service is stopped", severity = OrionFindingSeverity.WARNING,
            evidenceRefs = listOf("runtime snapshot"), detectedAtEpochMs = 100L
        )
        val report = OrionDiagnosticReport(100L, listOf(warning), 120, 2, 1, "STOPPED", null)
        assertTrue(report.healthy)
        assertTrue(report.promptBlock().contains("does not prove provider/network health"))
        val broken = report.copy(findings = listOf(warning.copy(severity = OrionFindingSeverity.CRITICAL)))
        assertFalse(broken.healthy)
        assertTrue(broken.promptBlock().contains("ISSUES NEED ATTENTION"))
    }

    @Test fun diagnosticFindingsRequireEvidenceAndTimestampBeforeAction() {
        val finding = OrionDiagnosticFinding(
            id = "finding-2", component = "tool_registry", title = "Missing route",
            detail = "Tool route missing", severity = OrionFindingSeverity.ERROR,
            evidenceRefs = listOf("ToolRegistry:missing"), detectedAtEpochMs = 200L
        )
        assertTrue(finding.isActionable())
        assertFalse(finding.copy(evidenceRefs = emptyList()).isActionable())
        assertFalse(finding.copy(detectedAtEpochMs = 0L).isActionable())
    }
}
