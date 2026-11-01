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
        File(context.filesDir, "orion_evidence_ledger.json").delete()
        context.deleteDatabase("orion_conversations.db")
        OrionConversationRepository.get(context).recordEvent(context, null, "test_seed", "ok", "test session")
        sessionId = requireNotNull(OrionConversationRepository.get(context).activeSessionId(context))
    }

    @After fun tearDown() {
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
        verifyRollback: () -> Boolean = { true }
    ) = OrionRepairLifecycle.execute(
        context, sessionId, "task-repair-test", finding(), proposal,
        OrionRepairLifecycle.expectedPlanDigest(sessionId, "task-repair-test", finding(), proposal),
        ownerApprovalDigest, sandbox, apply, verify, rollback, verifyRollback
    )

    @Test fun safeReversibleRepairRequiresSandboxAndVerifiedApplyReceipt() {
        var applied = 0
        val result = execute(apply = { applied++; "receipt-123" })
        assertEquals(OrionRepairLifecycle.Result.VERIFIED, result.result)
        assertEquals(1, applied)
        assertTrue(OrionEvidenceLedger.events(context, "task-repair-test")
            .any { it.tool == "repair_verified" && it.outcome == OrionEvidenceLedger.Outcome.VERIFIED })
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
            ownerApprovalDigest = "a".repeat(64),
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
}
