package com.phoneai.app

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Typed, app-owned diagnostics and repair adapters. Model input selects an ID, never a command. */
internal object OrionDiagnosticsCapabilityExecutor {
    private const val WORKSPACE_REPAIR_ID = "ensure_python_workspace_root"

    fun supports(tool: String): Boolean = tool in setOf("self_diagnose", "repair_apply")

    fun execute(
        tool: String,
        args: JSONObject,
        context: Context,
        confirm: (String) -> Boolean = { false }
    ): String? = when (tool) {
        "self_diagnose" -> OrionSelfDiagnostics.inspect(context, recordActivity = true).promptBlock()
        "repair_apply" -> applyRegisteredRepair(context, args.optString("repair_id"), confirm)
        else -> null
    }

    private fun applyRegisteredRepair(
        context: Context,
        repairId: String,
        confirm: (String) -> Boolean
    ): String {
        if (repairId != WORKSPACE_REPAIR_ID) {
            return "error: unsupported repair_id; available app-owned repair: $WORKSPACE_REPAIR_ID"
        }
        val app = context.applicationContext
        val workspace = OrionPythonWorkspace(app)
        when (workspace.rootStatus()) {
            OrionPythonWorkspace.RootStatus.READY -> return "No repair needed: the app-private Python workspace is healthy."
            OrionPythonWorkspace.RootStatus.UNSAFE -> return "error: workspace path is suspicious or unsafe; automatic repair is blocked for owner inspection."
            OrionPythonWorkspace.RootStatus.MISSING -> Unit
        }
        if (!workspace.canEnsureRoot()) return "error: workspace preflight failed; parent path is unavailable or unsafe."

        val repository = OrionConversationRepository.get(app)
        val sessionId = repository.activeSessionId(app)?.takeIf { it.isNotBlank() }
            ?: return "error: there is no active conversation session to attach repair evidence to."
        val now = System.currentTimeMillis()
        val taskId = "repair-python-workspace-${sessionId.take(32)}"
        val finding = OrionDiagnosticFinding(
            id = "python-workspace-root-missing",
            component = "python_workspace",
            title = "Python workspace root is missing",
            detail = "The fixed app-private workspace root is absent; no user files are being deleted or moved.",
            severity = OrionFindingSeverity.WARNING,
            evidenceRefs = listOf("OrionPythonWorkspace:root_missing"),
            detectedAtEpochMs = now
        )
        val proposal = OrionRepairProposal(
            id = WORKSPACE_REPAIR_ID,
            findingId = finding.id,
            summary = "Create the fixed app-private Python workspace directory only.",
            risk = OrionRepairRisk.REVERSIBLE,
            isIdempotent = true,
            hasRollback = true,
            requiredOwnerApproval = false,
            suspicious = false
        )
        val boundary = OrionRepairExecutionBoundary.DEVICE_LOCAL
        val digest = OrionRepairLifecycle.expectedPlanDigest(sessionId, taskId, finding, proposal, boundary)
        val createdByThisRun = AtomicBoolean(false)
        val receipt = OrionCentralBrain.executeRepairProposal(
            context = app,
            sessionId = sessionId,
            taskId = taskId,
            finding = finding,
            proposal = proposal,
            planDigest = digest,
            requestOwnerApproval = confirm,
            sandboxTest = { workspace.canEnsureRoot() },
            apply = {
                when (workspace.ensureRootForRepair()) {
                    "created" -> { createdByThisRun.set(true); "workspace-root-created" }
                    "already_ready" -> "workspace-root-already-ready"
                    else -> null
                }
            },
            verify = { workspace.rootStatus() == OrionPythonWorkspace.RootStatus.READY },
            rollback = { !createdByThisRun.get() || workspace.removeRootIfEmpty() },
            verifyRollback = {
                if (createdByThisRun.get()) workspace.rootStatus() == OrionPythonWorkspace.RootStatus.MISSING
                else workspace.rootStatus() == OrionPythonWorkspace.RootStatus.READY
            },
            executionBoundary = boundary
        )
        return "Repair ${receipt.result.name.lowercase()}: ${receipt.detail}"
    }
}
