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
        finding: OrionDiagnosticFinding, proposal: OrionRepairProposal,
        executionBoundary: OrionRepairExecutionBoundary = OrionRepairExecutionBoundary.DEVICE_LOCAL
    ): String {
        fun field(value: String): String = "${value.length}:$value"
        val canonical = listOf(
            sessionId, taskId, finding.id, finding.component, finding.title, finding.detail,
            finding.severity.name, finding.detectedAtEpochMs.toString(),
            finding.evidenceRefs.sorted().joinToString("") { field(it) },
            proposal.id, proposal.findingId, proposal.summary, proposal.risk.name,
            proposal.isIdempotent.toString(), proposal.hasRollback.toString(),
            proposal.requiredOwnerApproval.toString(), proposal.suspicious.toString(), executionBoundary.name
        ).joinToString("") { field(it) }
        return sha256(canonical)
    }

    fun stageEventId(taskId: String, planDigest: String, stage: String): String =
        "repair-" + sha256("$taskId|${planDigest.lowercase()}|${stage.uppercase()}").take(48)

    private fun hasStageReceipt(
        context: Context, sessionId: String, taskId: String, planDigest: String, stage: String
    ): Boolean {
        val ledgerHas = OrionEvidenceLedger.events(context, taskId).any {
            it.backend == "repair_lifecycle" && it.tool == "repair_${stage.lowercase()}" &&
                it.argsFingerprint == planDigest
        }
        val chatHas = runCatching {
            OrionConversationRepository.get(context).hasEventForSession(
                context, sessionId, stageEventId(taskId, planDigest, stage)
            )
        }.getOrDefault(false)
        return ledgerHas || chatHas
    }

    /** Idempotently mirrors a lifecycle stage into both durable stores. */
    private fun emit(context: Context, sessionId: String, taskId: String, stage: String,
        planDigest: String, summary: String, verified: Boolean = false, failed: Boolean = false): Boolean {
        val ledgerHas = OrionEvidenceLedger.events(context, taskId).any {
            it.backend == "repair_lifecycle" && it.tool == "repair_${stage.lowercase()}" &&
                it.argsFingerprint == planDigest
        }
        val eventId = stageEventId(taskId, planDigest, stage)
        val repository = OrionConversationRepository.get(context)
        val chatHas = runCatching { repository.hasEventForSession(context, sessionId, eventId) }.getOrDefault(false)
        val ledgerSaved = ledgerHas || OrionEvidenceLedger.recordRepairStage(
            context, taskId, stage, planDigest, summary, verified, failed
        )
        val chatSaved = chatHas || runCatching {
            repository.recordEventForSession(
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
        verify: () -> Boolean, rollback: () -> Boolean, verifyRollback: () -> Boolean,
        executionBoundary: OrionRepairExecutionBoundary = OrionRepairExecutionBoundary.DEVICE_LOCAL
    ): Receipt {
        val app = context.applicationContext
        val digest = planDigest.lowercase()
        val expectedDigest = if (sessionId.isNotBlank() && taskId.isNotBlank() &&
            finding.isActionable() && finding.id == proposal.findingId && proposal.id.isNotBlank() &&
            proposal.summary.isNotBlank() && proposal.hasRollback) {
            expectedPlanDigest(sessionId, taskId, finding, proposal, executionBoundary)
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
                "Repair proposal recorded; risk=${proposal.risk}; boundary=${executionBoundary.name}; approval_required=${proposal.requiredOwnerApproval || proposal.suspicious}.")) {
            return Receipt(Result.PERSISTENCE_FAILED, digest, "Proposal receipt failed; repair was not tested or applied.")
        }

        // Resume a previously tested plan after process death or an owner-approval pause.
        // A durable SANDBOX_PASSED receipt is sufficient to skip the isolated test; a failed
        // test is terminal for this digest and is never silently rerun as though it had passed.
        val sandboxPassed = when {
            hasStageReceipt(app, sessionId, taskId, digest, "SANDBOX_FAILED") -> false
            hasStageReceipt(app, sessionId, taskId, digest, "SANDBOX_PASSED") -> true
            else -> {
                val passed = runCatching { sandboxTest() }.getOrDefault(false)
                val stage = if (passed) "SANDBOX_PASSED" else "SANDBOX_FAILED"
                val saved = emit(app, sessionId, taskId, stage, digest,
                    if (passed) "Isolated repair test passed." else "Isolated repair test failed or was uncertain.",
                    failed = !passed)
                if (!saved) return Receipt(Result.PERSISTENCE_FAILED, digest,
                    "Sandbox receipt failed; application is blocked and the test result must be reconciled.")
                passed
            }
        }
        if (!sandboxPassed) return Receipt(Result.SANDBOX_FAILED, digest, "Sandbox validation failed; repair was not applied.")

        // Only the trusted adapter can declare the execution boundary. Sandbox data mutations
        // are autonomous when isolated, idempotent and rollback-backed; dangerous/suspicious
        // work and every external effect still require owner approval.
        val approvalRequired = OrionSelfHealingPolicy.requiresOwnerApproval(proposal, executionBoundary)
        val safeAutoApply = OrionSelfHealingPolicy.mayAutoApply(
            proposal.copy(status = OrionRepairStatus.TEST_PASSED, testPassed = true),
            executionBoundary
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
                "Repair does not meet the risk-aware, boundary-specific, idempotent and rollback-backed auto-apply policy.")
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

    /**
     * Provider-neutral repair history for the Central Brain. Only sanitized lifecycle
     * summaries and opaque task identifiers are exposed; plan digests and callback payloads
     * are never added to the model context.
     */
    fun promptBlock(context: Context, limit: Int = 10): String {
        val recent = OrionEvidenceLedger.events(context.applicationContext)
            .filter { it.backend == "repair_lifecycle" && it.tool.startsWith("repair_") }
            .sortedByDescending { it.finishedAt }
            .take(limit.coerceIn(1, 20))
        if (recent.isEmpty()) return ""
        return buildString {
            appendLine("PERSISTED REPAIR / OWNER-APPROVAL LIFECYCLE")
            recent.asReversed().forEach { event ->
                val stage = event.tool.removePrefix("repair_").uppercase()
                appendLine("- task=${event.taskId}; stage=$stage; outcome=${event.outcome}; ${event.summary.take(220)}")
            }
            appendLine("These are durable repair receipts, not permission. A proposal is not owner approval. Revalidate the exact plan digest, current finding, sandbox result and owner decision before application. Never replay APPLY_STARTED/APPLY_UNCERTAIN plans; reconcile actual state first.")
        }.take(2_400)
    }
}
