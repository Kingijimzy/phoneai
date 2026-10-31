package com.phoneai.app

/**
 * Deterministic policy boundary for Orion's self-diagnosis, recovery and extension proposals.
 *
 * This layer deliberately does not execute repairs or generated code. It validates evidence
 * and lifecycle transitions; concrete diagnostics, sandbox runners and approval UI are adapters.
 */
enum class OrionFindingSeverity { INFO, WARNING, ERROR, CRITICAL }
enum class OrionFindingStatus { OPEN, INVESTIGATING, RESOLVED, ACCEPTED_RISK, DISMISSED }
enum class OrionRepairRisk { REVERSIBLE, DATA_AFFECTING, SECURITY_SENSITIVE, EXTERNAL_SIDE_EFFECT }
enum class OrionRepairStatus { PROPOSED, TESTING, TEST_FAILED, TEST_PASSED, AWAITING_OWNER, APPLIED, VERIFIED, ROLLED_BACK, REJECTED }
enum class OrionExtensionStatus { DRAFT, RESEARCHED, LICENSE_REVIEWED, TESTED, AWAITING_OWNER, ACTIVE, REJECTED, ROLLED_BACK }

data class OrionDiagnosticFinding(
    val id: String,
    val component: String,
    val title: String,
    val detail: String,
    val severity: OrionFindingSeverity,
    val evidenceRefs: List<String>,
    val detectedAtEpochMs: Long,
    val status: OrionFindingStatus = OrionFindingStatus.OPEN
) {
    fun isActionable(): Boolean = id.isNotBlank() && component.isNotBlank() &&
        title.isNotBlank() && evidenceRefs.isNotEmpty() && detectedAtEpochMs > 0
}

data class OrionRepairProposal(
    val id: String,
    val findingId: String,
    val summary: String,
    val risk: OrionRepairRisk,
    val isIdempotent: Boolean,
    val hasRollback: Boolean,
    val requiredOwnerApproval: Boolean,
    val status: OrionRepairStatus = OrionRepairStatus.PROPOSED,
    val testRunId: String? = null,
    val testPassed: Boolean = false
)

data class OrionExtensionDraft(
    val id: String,
    val name: String,
    val purpose: String,
    val sourceUrls: List<String>,
    val licenseId: String?,
    val licenseEvidence: String?,
    val declaredCapabilities: Set<String>,
    val sandboxTestRunId: String?,
    val sandboxTestsPassed: Boolean,
    val rollbackPlan: String,
    val status: OrionExtensionStatus = OrionExtensionStatus.DRAFT
)

object OrionSelfHealingPolicy {
    /** Only one bounded retry is allowed, and only for a known, idempotent, reversible operation. */
    fun mayAutoRetry(
        risk: OrionRepairRisk,
        idempotent: Boolean,
        hasRollback: Boolean,
        attemptCount: Int,
        findingSeverity: OrionFindingSeverity
    ): Boolean = risk == OrionRepairRisk.REVERSIBLE &&
        idempotent && hasRollback && attemptCount == 0 &&
        findingSeverity != OrionFindingSeverity.CRITICAL

    /** Code/config changes are never silently applied by a model-generated proposal. */
    fun mayAutoApply(proposal: OrionRepairProposal): Boolean =
        proposal.risk == OrionRepairRisk.REVERSIBLE &&
            proposal.isIdempotent && proposal.hasRollback &&
            proposal.status == OrionRepairStatus.TEST_PASSED &&
            proposal.testPassed && !proposal.requiredOwnerApproval

    fun nextRepairStatus(
        current: OrionRepairStatus,
        testsPassed: Boolean,
        ownerApproved: Boolean,
        applied: Boolean,
        verificationPassed: Boolean
    ): OrionRepairStatus? = when (current) {
        OrionRepairStatus.PROPOSED -> if (testsPassed) OrionRepairStatus.TEST_PASSED else OrionRepairStatus.TEST_FAILED
        OrionRepairStatus.TESTING -> if (testsPassed) OrionRepairStatus.TEST_PASSED else OrionRepairStatus.TEST_FAILED
        OrionRepairStatus.TEST_PASSED -> if (ownerApproved) OrionRepairStatus.AWAITING_OWNER else OrionRepairStatus.AWAITING_OWNER
        OrionRepairStatus.AWAITING_OWNER -> if (ownerApproved && applied) OrionRepairStatus.APPLIED else null
        OrionRepairStatus.APPLIED -> if (verificationPassed) OrionRepairStatus.VERIFIED else OrionRepairStatus.ROLLED_BACK
        OrionRepairStatus.TEST_FAILED -> null
        OrionRepairStatus.VERIFIED, OrionRepairStatus.ROLLED_BACK, OrionRepairStatus.REJECTED -> null
    }

    fun validateExtension(draft: OrionExtensionDraft): List<String> = buildList {
        if (draft.id.isBlank()) add("Extension ID is required")
        if (draft.name.isBlank()) add("Extension name is required")
        if (draft.purpose.isBlank()) add("Extension purpose is required")
        if (draft.sourceUrls.isEmpty() || draft.sourceUrls.any { !it.startsWith("https://") }) {
            add("Every research source must be an HTTPS URL")
        }
        if (draft.licenseId.isNullOrBlank() || draft.licenseEvidence.isNullOrBlank()) {
            add("License identity and evidence are required")
        }
        if (draft.declaredCapabilities.isEmpty()) add("At least one declared capability is required")
        if (draft.sandboxTestRunId.isNullOrBlank() || !draft.sandboxTestsPassed) {
            add("A successful sandbox test run is required")
        }
        if (draft.rollbackPlan.isBlank()) add("A rollback plan is required")
    }

    fun nextExtensionStatus(draft: OrionExtensionDraft): OrionExtensionStatus {
        if (validateExtension(draft).isNotEmpty()) return OrionExtensionStatus.DRAFT
        return when (draft.status) {
            OrionExtensionStatus.DRAFT -> OrionExtensionStatus.RESEARCHED
            OrionExtensionStatus.RESEARCHED -> OrionExtensionStatus.LICENSE_REVIEWED
            OrionExtensionStatus.LICENSE_REVIEWED -> OrionExtensionStatus.TESTED
            OrionExtensionStatus.TESTED -> OrionExtensionStatus.AWAITING_OWNER
            OrionExtensionStatus.AWAITING_OWNER -> OrionExtensionStatus.ACTIVE
            OrionExtensionStatus.ACTIVE -> OrionExtensionStatus.ACTIVE
            OrionExtensionStatus.REJECTED -> OrionExtensionStatus.REJECTED
            OrionExtensionStatus.ROLLED_BACK -> OrionExtensionStatus.ROLLED_BACK
        }
    }

    fun canActivateExtension(draft: OrionExtensionDraft, ownerApproved: Boolean): Boolean =
        validateExtension(draft).isEmpty() &&
            draft.status == OrionExtensionStatus.AWAITING_OWNER && ownerApproved
}
