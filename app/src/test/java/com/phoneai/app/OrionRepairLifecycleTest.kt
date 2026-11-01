package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionRepairLifecycleTest {
    private lateinit var context: Context
    private lateinit var sessionId: String

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Reset the helper itself: Robolectric may give each test a different Application
        // context while the process singleton survives, so close-only can retain a stale Context.
        OrionConversationRepository.resetForTests()
        // Clear migration/session preferences as well as SQLite: otherwise a prior test's
        // ACTIVE_ID and MIGRATED flag can leak a stale session identity into this fixture.
        context.getSharedPreferences("orion_chat_history", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "orion_evidence_ledger.json").delete()
        context.deleteDatabase("orion_conversations.db")
        OrionConversationRepository.get(context).recordEvent(context, null, "test_seed", "ok", "test session")
        sessionId = requireNotNull(OrionConversationRepository.get(context).activeSessionId(context))
    }

    @After fun tearDown() {
        OrionConversationRepository.resetForTests()
        context.getSharedPreferences("orion_chat_history", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "orion_evidence_ledger.json").delete()
        context.deleteDatabase("orion_conversations.db")
    }

    private fun finding() = OrionDiagnosticFinding(
        id = "finding-1", component = "test", title = "Known recoverable issue",
        detail = "Test fixture", severity = OrionFindingSeverity.WARNING,
        evidenceRefs = listOf("unit-test-evidence"), detectedAtEpochMs = 1_700_000_000_000L
    )

    private fun proposal(risk: OrionRepairRisk = OrionRepairRisk.REVERSIBLE, approval: Boolean = false) =
        OrionRepairProposal(
            id = "repair-1", findingId = "finding-1", summary = "Apply fixed app-owned repair",
            risk = risk, isIdempotent = true, hasRollback = true,
            requiredOwnerApproval = approval
        )

    private fun execute(
        proposal: OrionRepairProposal = proposal(),
        ownerApprovalDigest: String? = null,
        sandbox: () -> Boolean = { true },
        apply: () -> String? = { "receipt-123" },
        verify: () -> Boolean = { true },
        rollback: () -> Boolean = { true },
        verifyRollback: () -> Boolean = { true },
        executionBoundary: OrionRepairExecutionBoundary = OrionRepairExecutionBoundary.DEVICE_LOCAL
    ) = OrionRepairLifecycle.execute(
        context, sessionId, "task-repair-test", finding(), proposal,
        OrionRepairLifecycle.expectedPlanDigest(sessionId, "task-repair-test", finding(), proposal, executionBoundary),
        ownerApprovalDigest, sandbox, apply, verify, rollback, verifyRollback, executionBoundary
    )

    @Test fun safeReversibleRepairRequiresSandboxAndVerifiedApplyReceipt() {
        var applied = 0
        val result = execute(apply = { applied++; "receipt-123" })
        assertEquals(OrionRepairLifecycle.Result.VERIFIED, result.result)
        assertEquals(1, applied)
        assertTrue(OrionEvidenceLedger.events(context, "task-repair-test")
            .any { it.tool == "repair_verified" && it.outcome == OrionEvidenceLedger.Outcome.VERIFIED })
    }

    @Test fun ownerApprovalResumeReusesSandboxReceiptAfterProcessLikeReentry() {
        val plan = proposal(OrionRepairRisk.DATA_AFFECTING, approval = true)
        val digest = OrionRepairLifecycle.expectedPlanDigest(
            sessionId, "task-repair-test", finding(), plan
        )
        var sandboxCalls = 0
        var applyCalls = 0
        val waiting = execute(
            proposal = plan,
            sandbox = { sandboxCalls++; true },
            apply = { applyCalls++; "should-wait" }
        )
        assertEquals(OrionRepairLifecycle.Result.AWAITING_OWNER, waiting.result)
        assertEquals(1, sandboxCalls)
        assertEquals(0, applyCalls)

        val resumed = execute(
            proposal = plan,
            ownerApprovalDigest = digest,
            sandbox = { sandboxCalls++; true },
            apply = { applyCalls++; "approved-receipt" }
        )
        assertEquals(OrionRepairLifecycle.Result.VERIFIED, resumed.result)
        assertEquals(1, sandboxCalls)
        assertEquals(1, applyCalls)
        val stages = OrionEvidenceLedger.events(context, "task-repair-test")
            .filter { it.argsFingerprint == digest && it.backend == "repair_lifecycle" }
            .map { it.tool }
        assertEquals(1, stages.count { it == "repair_proposed" })
        assertEquals(1, stages.count { it == "repair_sandbox_passed" })
        assertEquals(1, stages.count { it == "repair_owner_approved" })
        assertEquals(1, stages.count { it == "repair_verified" })
    }

    @Test fun sandboxDataMutationRunsAutonomouslyAfterIsolatedTest() {
        val sandboxProposal = proposal(OrionRepairRisk.DATA_AFFECTING, approval = false)
        var applied = 0
        val result = execute(
            proposal = sandboxProposal,
            apply = { applied++; "sandbox-receipt" },
            executionBoundary = OrionRepairExecutionBoundary.ISOLATED_SANDBOX
        )
        assertEquals(OrionRepairLifecycle.Result.VERIFIED, result.result)
        assertEquals(1, applied)
    }

    @Test fun securitySensitiveSandboxRepairStillRequiresOwnerApproval() {
        val securityProposal = proposal(OrionRepairRisk.SECURITY_SENSITIVE, approval = false)
        var applied = false
        val result = execute(
            proposal = securityProposal,
            apply = { applied = true; "receipt" },
            executionBoundary = OrionRepairExecutionBoundary.ISOLATED_SANDBOX
        )
        assertEquals(OrionRepairLifecycle.Result.AWAITING_OWNER, result.result)
        assertFalse(applied)
    }

    @Test fun suspiciousSandboxProposalRequiresOwnerApproval() {
        val suspiciousProposal = proposal().copy(suspicious = true)
        var applied = false
        val result = execute(
            proposal = suspiciousProposal,
            apply = { applied = true; "receipt" },
            executionBoundary = OrionRepairExecutionBoundary.ISOLATED_SANDBOX
        )
        assertEquals(OrionRepairLifecycle.Result.AWAITING_OWNER, result.result)
        assertFalse(applied)
    }

    @Test fun externalBoundaryNeverAutoAppliesWithoutOwnerApproval() {
        val externalProposal = proposal(OrionRepairRisk.REVERSIBLE, approval = false)
        var applied = false
        val result = execute(
            proposal = externalProposal,
            apply = { applied = true; "receipt" },
            executionBoundary = OrionRepairExecutionBoundary.EXTERNAL
        )
        assertEquals(OrionRepairLifecycle.Result.AWAITING_OWNER, result.result)
        assertFalse(applied)
    }

    @Test fun consequentialRepairWaitsForOwnerAndNeverCallsApplyEarly() {
        var applied = 0
        val result = execute(
            proposal = proposal(OrionRepairRisk.DATA_AFFECTING, true),
            ownerApprovalDigest = null,
            apply = { applied++; "receipt" }
        )
        assertEquals(OrionRepairLifecycle.Result.AWAITING_OWNER, result.result)
        assertEquals(0, applied)
        assertTrue(OrionEvidenceLedger.events(context, "task-repair-test")
            .any { it.tool == "repair_awaiting_owner" })
    }

    @Test fun approvalForAnotherDigestCannotAuthorizeThisPlan() {
        var applied = false
        val result = execute(
            proposal = proposal(OrionRepairRisk.DATA_AFFECTING, true),
            ownerApprovalDigest = "b".repeat(64),
            apply = { applied = true; "receipt" }
        )
        assertEquals(OrionRepairLifecycle.Result.AWAITING_OWNER, result.result)
        assertFalse(applied)
    }

    @Test fun failedSandboxPreventsApplication() {
        var applied = false
        val result = execute(sandbox = { false }, apply = { applied = true; "receipt" })
        assertEquals(OrionRepairLifecycle.Result.SANDBOX_FAILED, result.result)
        assertFalse(applied)
    }

    @Test fun failedVerificationTriggersRollbackAndRecordsItsReceipt() {
        var rolledBack = false
        val result = execute(
            proposal = proposal(OrionRepairRisk.DATA_AFFECTING, true),
            ownerApprovalDigest = OrionRepairLifecycle.expectedPlanDigest(
                sessionId, "task-repair-test", finding(),
                proposal(OrionRepairRisk.DATA_AFFECTING, true)
            ),
            verify = { false },
            rollback = { rolledBack = true; true }
        )
        assertEquals(OrionRepairLifecycle.Result.ROLLED_BACK, result.result)
        assertTrue(rolledBack)
        assertTrue(OrionEvidenceLedger.events(context, "task-repair-test")
            .any { it.tool == "repair_rolled_back" })
    }

    @Test fun rollbackCallbackWithoutRestoredStateProofIsNotReportedAsRolledBack() {
        var rollbackInvoked = false
        var restoredCheckInvoked = false
        val result = execute(
            proposal = proposal(OrionRepairRisk.DATA_AFFECTING, true),
            ownerApprovalDigest = OrionRepairLifecycle.expectedPlanDigest(
                sessionId, "task-repair-test", finding(),
                proposal(OrionRepairRisk.DATA_AFFECTING, true)
            ),
            verify = { false },
            rollback = { rollbackInvoked = true; true },
            verifyRollback = { restoredCheckInvoked = true; false }
        )
        assertEquals(OrionRepairLifecycle.Result.ROLLBACK_FAILED, result.result)
        assertTrue(rollbackInvoked)
        assertTrue(restoredCheckInvoked)
        assertTrue(OrionEvidenceLedger.events(context, "task-repair-test")
            .any { it.tool == "repair_rollback_failed" && it.outcome == OrionEvidenceLedger.Outcome.FAILED })
    }

    @Test fun missingApplyReceiptIsUncertainAndNeverReplayed() {
        var calls = 0
        val result = execute(apply = { calls++; null })
        assertEquals(OrionRepairLifecycle.Result.APPLY_OUTCOME_UNCERTAIN, result.result)
        assertEquals(1, calls)
        assertTrue(OrionEvidenceLedger.events(context, "task-repair-test")
            .any { it.tool == "repair_apply_uncertain" })
    }

    @Test fun previouslyAppliedPlanCannotBeReplayedAfterProcessLikeReentry() {
        var calls = 0
        val first = execute(apply = { calls++; "receipt-once" })
        assertEquals(OrionRepairLifecycle.Result.VERIFIED, first.result)
        val second = execute(apply = { calls++; "receipt-twice" })
        assertEquals(OrionRepairLifecycle.Result.POLICY_BLOCKED, second.result)
        assertEquals(1, calls)
    }

    @Test fun appliedPlanCannotBeReplayedAfterEvidenceLedgerRotation() {
        var calls = 0
        val first = execute(apply = { calls++; "receipt-once" })
        assertEquals(OrionRepairLifecycle.Result.VERIFIED, first.result)
        repeat(260) { index ->
            OrionEvidenceLedger.recordRecovery(context, "noise-$index", "test", "bounded", "rotation")
        }
        val second = execute(apply = { calls++; "receipt-twice" })
        assertEquals(OrionRepairLifecycle.Result.POLICY_BLOCKED, second.result)
        assertEquals(1, calls)
    }

    @Test fun applyStartClaimIsAtomicAndSingleUse() {
        val plan = proposal()
        val digest = OrionRepairLifecycle.expectedPlanDigest(
            sessionId, "task-repair-test", finding(), plan, OrionRepairExecutionBoundary.EXTERNAL
        )
        val eventId = OrionRepairLifecycle.stageEventId("task-repair-test", digest, "APPLY_STARTED")
        val repository = OrionConversationRepository.get(context)
        assertTrue(repository.claimEventForSession(
            context, sessionId, eventId, "repair_lifecycle", "apply_started", "first claimant"
        ))
        assertFalse(repository.claimEventForSession(
            context, sessionId, eventId, "repair_lifecycle", "apply_started", "second claimant"
        ))
    }

    @Test fun persistedApplyStartedMarkerBlocksReplayAfterInterruptedDispatch() {
        val plan = proposal()
        val digest = OrionRepairLifecycle.expectedPlanDigest(sessionId, "task-repair-test", finding(), plan)
        assertTrue(OrionEvidenceLedger.recordRepairStage(
            context, "task-repair-test", "APPLY_STARTED", digest,
            "Simulated process interruption during adapter dispatch."
        ))
        assertTrue(OrionConversationRepository.get(context).recordEventForSession(
            context, sessionId,
            OrionRepairLifecycle.stageEventId("task-repair-test", digest, "APPLY_STARTED"),
            "repair_lifecycle", "apply_started", "Simulated interrupted dispatch."
        ) != null)
        var applyCalls = 0
        val result = execute(apply = { applyCalls++; "should-not-run" })
        assertEquals(OrionRepairLifecycle.Result.POLICY_BLOCKED, result.result)
        assertEquals(0, applyCalls)
    }

    @Test fun suppliedDigestMustMatchTheExactFindingAndProposal() {
        var applied = false
        val original = proposal()
        val changed = original.copy(summary = "Different repair plan")
        val staleDigest = OrionRepairLifecycle.expectedPlanDigest(sessionId, "task-repair-test", finding(), original)
        val result = OrionRepairLifecycle.execute(
            context, sessionId, "task-repair-test", finding(), changed, staleDigest, null,
            { true }, { applied = true; "receipt" }, { true }, { true }, { true }
        )
        assertEquals(OrionRepairLifecycle.Result.POLICY_BLOCKED, result.result)
        assertFalse(applied)
    }

    @Test fun invalidPlanDigestIsRejectedBeforeAnyCallback() {
        var callbacks = 0
        val result = OrionRepairLifecycle.execute(
            context, sessionId, "task-repair-test", finding(), proposal(), "bad", null,
            { callbacks++; true }, { callbacks++; "receipt" }, { callbacks++; true }, { callbacks++; true }, { callbacks++; true }
        )
        assertEquals(OrionRepairLifecycle.Result.POLICY_BLOCKED, result.result)
        assertEquals(0, callbacks)
    }

    @Test fun centralBrainRequestsOwnerOnlyAfterSandboxForDangerousRepairAndResumesWithoutRetesting() {
        val plan = proposal(OrionRepairRisk.SECURITY_SENSITIVE, approval = true)
        val digest = OrionRepairLifecycle.expectedPlanDigest(sessionId, "task-repair-test", finding(), plan)
        var sandboxCalls = 0
        var approvalCalls = 0
        var applyCalls = 0
        val receipt = OrionCentralBrain.executeRepairProposal(
            context, sessionId, "task-repair-test", finding(), plan, digest,
            { prompt ->
                approvalCalls++
                assertTrue(prompt.contains("SECURITY_SENSITIVE"))
                assertTrue(prompt.contains("safely tested"))
                assertEquals(1, sandboxCalls)
                true
            },
            { sandboxCalls++; true },
            { applyCalls++; "approved-repair-receipt" },
            { true }, { true }, { true }
        )
        assertEquals(OrionRepairLifecycle.Result.VERIFIED, receipt.result)
        assertEquals(1, sandboxCalls)
        assertEquals(1, approvalCalls)
        assertEquals(1, applyCalls)
    }

    @Test fun centralBrainPersistsOwnerRejectionAndNeverApplies() {
        val plan = proposal(OrionRepairRisk.EXTERNAL_SIDE_EFFECT, approval = true)
        val digest = OrionRepairLifecycle.expectedPlanDigest(sessionId, "task-repair-test", finding(), plan)
        var applyCalls = 0
        val rejected = OrionCentralBrain.executeRepairProposal(
            context, sessionId, "task-repair-test", finding(), plan, digest,
            { false }, { true }, { applyCalls++; "must-not-run" }, { true }, { true }, { true },
            OrionRepairExecutionBoundary.EXTERNAL
        )
        assertEquals(OrionRepairLifecycle.Result.OWNER_REJECTED, rejected.result)
        assertEquals(0, applyCalls)
        val replay = OrionRepairLifecycle.execute(
            context, sessionId, "task-repair-test", finding(), plan, digest, digest,
            { true }, { applyCalls++; "replay" }, { true }, { true }, { true },
            OrionRepairExecutionBoundary.EXTERNAL
        )
        assertEquals(OrionRepairLifecycle.Result.POLICY_BLOCKED, replay.result)
        assertEquals(0, applyCalls)
    }

    @Test fun centralBrainRepairContextIncludesPersistedStagesWithoutApprovalDigest() {
        val digest = "d".repeat(64)
        assertTrue(OrionEvidenceLedger.recordRepairStage(
            context, "task-repair-test", "AWAITING_OWNER", digest,
            "Sandbox passed; explicit owner approval is required before application."
        ))
        val block = OrionRepairLifecycle.promptBlock(context)
        assertTrue(block.contains("PERSISTED REPAIR / OWNER-APPROVAL LIFECYCLE"))
        assertTrue(block.contains("AWAITING_OWNER"))
        assertFalse(block.contains(digest))
        assertFalse(block.contains("task-repair-test"))
    }

    @Test fun centralBrainEntryPointUsesTheGuardedRepairLifecycle() {
        val plan = proposal()
        val digest = OrionRepairLifecycle.expectedPlanDigest(sessionId, "task-repair-test", finding(), plan)
        var applied = 0
        var approvalPrompts = 0
        val receipt = OrionCentralBrain.executeRepairProposal(
            context, sessionId, "task-repair-test", finding(), plan, digest,
            { approvalPrompts++; true },
            { true }, { applied++; "central-brain-receipt" }, { true }, { true }, { true }
        )
        assertEquals(OrionRepairLifecycle.Result.VERIFIED, receipt.result)
        assertEquals(1, applied)
        assertTrue(OrionEvidenceLedger.events(context, "task-repair-test")
            .any { it.tool == "repair_verified" && it.outcome == OrionEvidenceLedger.Outcome.VERIFIED })
    }
}
