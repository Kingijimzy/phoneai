package com.phoneai.app

import kotlinx.serialization.Serializable

/**
 * Phase 0C: Mission Ownership
 *
 * Authoritative mission lifecycle state machine.
 * Every mission flows through these states with enforced transitions.
 */
internal enum class OrionMissionStatus {
    INTAKE,       // New objective received, intent analyzed, decision pending
    PLANNED,      // Decision made, plan built, awaiting confirmation or execution
    EXECUTING,    // Plan steps are being dispatched
    VERIFYING,    // All steps done; objective-level verification running
    COMPLETED,    // Verification passed; mission evidence recorded
    SUSPENDED,    // Paused by owner or power controller; state checkpointed
    CHECKPOINTED, // Explicit checkpoint (e.g., before long-running step)
    FAILED,       // Unrecoverable after replanning exhausted; recovery rationale recorded
    CANCELLED     // Owner cancelled; no further work will be done
}

internal sealed class MissionTransition(open val from: OrionMissionStatus, open val to: OrionMissionStatus) {
    data class Valid(override val from: OrionMissionStatus, override val to: OrionMissionStatus) : MissionTransition(from, to)
    data class Invalid(override val from: OrionMissionStatus, override val to: OrionMissionStatus, val reason: String) : MissionTransition(from, to)
}

internal object MissionTransitionValidator {
    private val ALLOWED = mapOf<OrionMissionStatus, Set<OrionMissionStatus>>(
        OrionMissionStatus.INTAKE to setOf(OrionMissionStatus.PLANNED, OrionMissionStatus.CANCELLED),
        OrionMissionStatus.PLANNED to setOf(OrionMissionStatus.EXECUTING, OrionMissionStatus.SUSPENDED, OrionMissionStatus.CANCELLED),
        OrionMissionStatus.EXECUTING to setOf(
            OrionMissionStatus.VERIFYING,
            OrionMissionStatus.SUSPENDED,
            OrionMissionStatus.CHECKPOINTED,
            OrionMissionStatus.FAILED,
            OrionMissionStatus.CANCELLED,
            OrionMissionStatus.EXECUTING // replanning stays in EXECUTING
        ),
        OrionMissionStatus.VERIFYING to setOf(
            OrionMissionStatus.COMPLETED,
            OrionMissionStatus.FAILED,
            OrionMissionStatus.EXECUTING, // replan on verification failure
            OrionMissionStatus.SUSPENDED
        ),
        OrionMissionStatus.COMPLETED to setOf(OrionMissionStatus.INTAKE), // new mission can start
        OrionMissionStatus.SUSPENDED to setOf(OrionMissionStatus.EXECUTING, OrionMissionStatus.CANCELLED),
        OrionMissionStatus.CHECKPOINTED to setOf(OrionMissionStatus.EXECUTING, OrionMissionStatus.SUSPENDED, OrionMissionStatus.CANCELLED),
        OrionMissionStatus.FAILED to setOf(OrionMissionStatus.INTAKE, OrionMissionStatus.CANCELLED), // retry creates new INTAKE
        OrionMissionStatus.CANCELLED to setOf(OrionMissionStatus.INTAKE)
    )

    fun validate(from: OrionMissionStatus, to: OrionMissionStatus): MissionTransition {
        return if (from == to) {
            MissionTransition.Invalid(from, to, "No-op transition: $from -> $to")
        } else if (to in (ALLOWED[from] ?: emptySet())) {
            MissionTransition.Valid(from, to)
        } else {
            MissionTransition.Invalid(from, to, "Illegal transition: $from -> $to. Allowed: ${ALLOWED[from]?.joinToString(", ") ?: "none"}")
        }
    }

    fun isValid(from: OrionMissionStatus, to: OrionMissionStatus): Boolean =
        validate(from, to) is MissionTransition.Valid
}

/**
 * Specialist specialism types for the AgentFactory.
 */
@Serializable
internal enum class OrionSpecialism {
    RESEARCH("research", "Web research and information gathering", setOf("browser_navigate", "browser_snapshot", "live_web_search", "memory")),
    CODING("coding", "Code writing, testing, and debugging", setOf("python_exec", "shell", "workspace_read", "workspace_write", "save_file")),
    DIAGNOSTICS("diagnostics", "Failure analysis and system diagnostics", setOf("read_screen", "look", "shell", "battery", "device_info", "memory")),
    TRADING_ANALYSIS("trading_analysis", "Market analysis and trading research", setOf("trading_read", "trading_account", "browser_navigate", "memory")),
    VERIFICATION("verification", "Independent outcome verification", setOf("verify_action", "verify_send", "read_screen", "look")),
    PLANNING("planning", "Objective decomposition and strategy", setOf("memory", "read_screen", "shell")),
    DATA_ANALYSIS("data_analysis", "Data processing and analysis", setOf("python_exec", "shell", "workspace_read", "workspace_write")),
    BROWSER("browser", "Web automation and scraping", setOf("browser_navigate", "browser_click", "browser_type", "browser_snapshot", "live_web_search")),
    PHONE("phone", "Android app interaction and control", setOf("open_app", "click", "type_text", "swipe", "send_message", "read_screen")),
    SECURITY("security", "Permission auditing and security review", setOf("shell", "read_screen", "memory", "device_info"));

    val id: String
    val description: String
    val defaultTools: Set<String>

    constructor(id: String, description: String, defaultTools: Set<String>) {
        this.id = id
        this.description = description
        this.defaultTools = defaultTools
    }

    companion object {
        fun fromId(id: String): OrionSpecialism? = values().firstOrNull { it.id == id }
    }
}

/**
 * Specialist session result with verification status.
 */
@Serializable
internal data class OrionSpecialistReceipt(
    val sessionId: String,
    val parentMissionId: String,
    val specialism: OrionSpecialism,
    val objective: String,
    val result: String,
    val evidence: List<String>,
    val verdict: SpecialistVerdict,
    val startedAt: Long,
    val completedAt: Long,
    val toolCalls: Int
)

@Serializable
internal enum class SpecialistVerdict {
    VERIFIED,    // Evidence confirms the specialist's work achieved its objective
    UNVERIFIED,  // Specialist claimed success but parent cannot verify
    FAILED,      // Specialist failed to achieve the objective
    CANCELLED    // Specialist was terminated before completion
}

/**
 * Evidence aggregator for a mission - the single source of truth for observability.
 */
internal data class OrionMissionEvidence(
    val missionId: String,
    val decisionExplanation: String,
    val rejectedAlternatives: List<String>,
    val plan: String, // JSON of ExecutionPlan
    val executedSteps: List<StepEvidence>,
    val specialistReceipts: List<OrionSpecialistReceipt>,
    val failures: List<FailureEvidence>,
    val recoveryActions: List<String>,
    val finalVerification: String, // JSON of VerificationOutcome
    val completedAt: Long?
)

@Serializable
internal data class StepEvidence(
    val stepId: String,
    val tool: String,
    val args: String,
    val result: String,
    val verified: Boolean,
    val timestamp: Long
)

internal data class FailureEvidence(
    val stepId: String,
    val tool: String,
    val error: String,
    val recoveryAttempted: Boolean,
    val recoveryStrategy: String?,
    val timestamp: Long
)