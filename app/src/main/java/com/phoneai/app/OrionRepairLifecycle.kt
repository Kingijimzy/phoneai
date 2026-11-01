package com.phoneai.app

import android.content.Context
import java.security.MessageDigest

/**
 * Guarded repair transaction coordinator over Orion's existing task/session and evidence stores.
 * It never evaluates model-generated source. All callbacks must be app-owned adapters: sandboxTest
 * uses an isolated runner, apply performs one narrowly-scoped operation, verify inspects the real
 * postcondition, and rollback restores captured prior state. Uncertain receipts are never replayed.
 */
internal object OrionRepairLifecycle {
    enum class Result { VERIFIED, AWAITING_OWNER, SANDBOX_FAILED, POLICY_BLOCKED, APPLY_FAILED,
        APPLY_OUTCOME_UNCERTAIN, ROLLED_BACK, ROLLBACK_FAILED, PERSISTENCE_FAILED }

    data class Receipt(val result: Result, val planDigest: String, val detail: String)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /**
     * Stable, context-bound digest of the complete repair intent. execute recomputes it so a
     * stale digest cannot authorize changed finding/proposal content or a different task/session.
     */
    fun expectedPlanDigest(
        sessionId: String, taskId: String,
        finding: OrionDiagnosticFinding, proposal: OrionRepairProposal
    ): String {
        fun field(value: String): String = "${value.length}:$value"
        val canonical = listOf(
            sessionId, taskId, finding.id, finding.component, finding.title, finding.detail,
            finding.severity.name, finding.detectedAtEpochMs.toString(),
            finding.evidenceRefs.sorted().joinToString("") { field(it) },
            proposal.id, proposal.findingId, proposal.summary, proposal.risk.name,
            proposal.isIdempotent.toString(), proposal.hasRollback.toString(),
            proposal.requiredOwnerApproval.toString()
        ).joinToString("") { field(it) }
        return sha256(canonical)
    }

    fun stageEventId(taskId: String, planDigest: String, stage: String): String =
        "repair-" + sha256("$taskId|${planDigest.lowercase()}|${stage.uppercase()}").take(48)

    private fun emit(context: Context, sessionId: String, taskId: String, stage: String,
        planDigest: String, summary: String, verified: Boolean = false, failed: Boolean = false): Boolean {
        val ledgerSaved = OrionEvidenceLedger.recordRepairStage(
            context, taskId, stage, planDigest, summary, verified, failed
        )
        val eventId = stageEventId(taskId, planDigest, stage)
        val chatSaved = runCatching {
            OrionConversationRepository.get(context).recordEventForSession(
                context, sessionId, eventId, "repair_lifecycle", stage.lowercase(), summary
            ) != null
        }.getOrDefault(false)
        return ledgerSaved && chatSaved
    }

    /**
     * ownerApprovalDigest must be returned by explicit owner confirmation of this exact plan digest.
     * Callbacks are fixed application code, never model-generated code or arbitrary shell commands.
     */
    fun execute(
        context: Context, sessionId: String, taskId: String,
        finding: OrionDiagnosticFinding, proposal: OrionRepairProposal, planDigest: String,
        ownerApprovalDigest: String?, sandboxTest: () -> Boolean, apply: () -> String?,
        verify: () -> Boolean, rollback: () -> Boolean, verifyRollback: () -> Boolean
    ): Receipt {
        val app = context.applicationContext
        val digest = planDigest.lowercase()
        val expectedDigest = if (sessionId.isNotBlank() && taskId.isNotBlank() &&
            finding.isActionable() && finding.id == proposal.findingId && proposal.id.isNotBlank() &&
            proposal.summary.isNotBlank() && proposal.hasRollback) {
            expectedPlanDigest(sessionId, taskId, finding, proposal)
        } else ""
        val ownerApproved = ownerApprovalDigest?.equals(expectedDigest, ignoreCase = true) == true
        if (sessionId.isBlank() || taskId.isBlank() || !digest.matches(Regex("^[a-f0-9]{64}$")) ||
            digest != expectedDigest || !finding.isActionable() || finding.id != proposal.findingId || proposal.id.isBlank() ||
            proposal.summary.isBlank() || !proposal.hasRollback) {
            return Receipt(Result.POLICY_BLOCKED, digest,
                "Repair proposal failed identity, evidence, digest or rollback validation.")
        }

        // A plan may be applied only once. An interrupted/uncertain apply is reconciled
        // manually; a previous owner approval is never silently reused after process restart.
        val terminalOrUncertain = setOf("APPLY_STARTED", "APPLIED", "APPLY_UNCERTAIN", "VERIFIED",
            "ROLLBACK_REQUIRED", "ROLLED_BACK", "ROLLBACK_FAILED")
        val durableTerminalReceipt = terminalOrUncertain.any { stage ->
            val eventId = "repair-" + sha256("$taskId|$digest|${stage}").take(48)
            runCatching {
                OrionConversationRepository.get(app).hasEventForSession(app, sessionId, eventId)
            }.getOrDefault(false)
        }
        val priorPlanEvents = OrionEvidenceLedger.events(app, taskId).filter { it.argsFingerprint == digest }
        if (durableTerminalReceipt || priorPlanEvents.any { it.tool in terminalOrUncertain.map { stage -> "repair_" + stage.lowercase() } }) {
            return Receipt(Result.POLICY_BLOCKED, digest,
                "This exact repair plan already reached an apply or uncertain state; it cannot be replayed.")
        }

        if (!emit(app, sessionId, taskId, "PROPOSED", digest,
                "Repair proposal recorded; risk=${proposal.risk}; approval_required=${proposal.requiredOwnerApproval}.")) {
            return Receipt(Result.PERSISTENCE_FAILED, digest, "Proposal receipt failed; repair was not tested or applied.")
        }

        val sandboxPassed = runCatching { sandboxTest() }.getOrDefault(false)
        if (!emit(app, sessionId, taskId, if (sandboxPassed) "SANDBOX_PASSED" else "SANDBOX_FAILED",
                digest, if (sandboxPassed) "Isolated repair test passed." else "Isolated repair test failed or was uncertain.",
                failed = !sandboxPassed)) {
            return Receipt(Result.PERSISTENCE_FAILED, digest, "Sandbox receipt failed; application is blocked.")
        }
        if (!sandboxPassed) return Receipt(Result.SANDBOX_FAILED, digest, "Sandbox validation failed; repair was not applied.")

        val approvalRequired = proposal.requiredOwnerApproval ||
            proposal.risk != OrionRepairRisk.REVERSIBLE || !proposal.isIdempotent
        val safeAutoApply = OrionSelfHealingPolicy.mayAutoApply(
            proposal.copy(status = OrionRepairStatus.TEST_PASSED, testPassed = true)
        )
        if (approvalRequired && !ownerApproved) {
            val saved = emit(app, sessionId, taskId, "AWAITING_OWNER", digest,
                "Sandbox passed; explicit owner approval is required before application.")
            return Receipt(if (saved) Result.AWAITING_OWNER else Result.PERSISTENCE_FAILED, digest,
                "Repair is waiting for explicit owner approval.")
        }
        if (approvalRequired && ownerApproved) {
            if (!emit(app, sessionId, taskId, "OWNER_APPROVED", digest,
                    "Owner approved this exact repair plan digest.")) {
                return Receipt(Result.PERSISTENCE_FAILED, digest, "Approval receipt failed; application is blocked.")
            }
        } else if (!safeAutoApply) {
            return Receipt(Result.POLICY_BLOCKED, digest,
                "Repair does not meet the reversible, idempotent, rollback-backed auto-apply policy.")
        }

        // Atomically claim the one-shot transition before entering any side-effecting adapter.
        // A competing worker or process can never pass this unique event-ID insertion twice.
        val applyStartedId = stageEventId(taskId, digest, "APPLY_STARTED")
        val claimed = runCatching {
            OrionConversationRepository.get(app).claimEventForSession(
                app, sessionId, applyStartedId, "repair_lifecycle", "apply_started",
                "Application dispatch claimed; a crash or missing receipt requires manual reconciliation."
            )
        }.getOrDefault(false)
        if (!claimed) return Receipt(Result.POLICY_BLOCKED, digest,
            "Another execution already claimed this repair plan; reconcile its state before retrying.")
        // Mirror the claim into the evidence ledger before invoking the adapter.
        if (!OrionEvidenceLedger.recordRepairStage(
                app, taskId, "APPLY_STARTED", digest,
                "Application dispatch claimed; a crash or missing receipt requires manual reconciliation."
            )) {
            return Receipt(Result.PERSISTENCE_FAILED, digest, "Apply-start evidence failed; adapter was not invoked.")
        }
        val appliedReceipt = try { apply()?.trim()?.take(160) } catch (_: Exception) { null }
        if (appliedReceipt.isNullOrBlank()) {
            emit(app, sessionId, taskId, "APPLY_UNCERTAIN", digest,
                "Apply callback returned no receipt or raised an error; inspect actual state before retry.", failed = true)
            return Receipt(Result.APPLY_OUTCOME_UNCERTAIN, digest,
                "No replay attempted. Inspect actual state before further action.")
        }
        if (!emit(app, sessionId, taskId, "APPLIED", digest,
                "Application receipt recorded; receipt_fingerprint=${sha256(appliedReceipt).take(16)}.")) {
            return Receipt(Result.PERSISTENCE_FAILED, digest,
                "Apply may have occurred, but its receipt was not persisted. Do not replay.")
        }

        val verified = runCatching { verify() }.getOrDefault(false)
        if (verified) {
            val saved = emit(app, sessionId, taskId, "VERIFIED", digest,
                "Postcondition independently verified.", verified = true)
            return Receipt(if (saved) Result.VERIFIED else Result.PERSISTENCE_FAILED, digest,
                if (saved) "Repair applied and independently verified." else "Repair verified, but final evidence persistence failed.")
        }

        if (!emit(app, sessionId, taskId, "ROLLBACK_REQUIRED", digest,
                "Postcondition verification failed; rollback is required.", failed = true)) {
            return Receipt(Result.PERSISTENCE_FAILED, digest,
                "Verification failed and rollback intent could not be persisted; manual intervention required.")
        }
        val rollbackInvoked = runCatching { rollback() }.getOrDefault(false)
        // Callback completion is not proof of restoration; require a separate read-only check.
        val restored = rollbackInvoked && runCatching { verifyRollback() }.getOrDefault(false)
        val rollbackSaved = emit(app, sessionId, taskId, if (restored) "ROLLED_BACK" else "ROLLBACK_FAILED",
            digest, if (restored) "Rollback completed and original state independently verified."
            else "Rollback failed or was uncertain; manual intervention required.", verified = restored, failed = !restored)
        return when {
            !rollbackSaved -> Receipt(Result.PERSISTENCE_FAILED, digest, "Rollback outcome could not be persisted.")
            restored -> Receipt(Result.ROLLED_BACK, digest,
                "Verification failed; rollback completed and restored state was independently verified.")
            else -> Receipt(Result.ROLLBACK_FAILED, digest, "Rollback failed or was uncertain; manual intervention required.")
        }
    }
}
