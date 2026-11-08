package com.phoneai.app

import android.os.Parcelable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToString
import kotlinx.serialization.json.decodeFromString

/**
 * Phase 0C: Mission Ownership
 *
 * Authoritative mission record persisted through the entire lifecycle.
 * Contains everything needed to resume, recover, or inspect a mission.
 */
internal data class OrionMission(
    val id: String,                          // Unique mission ID (e.g., "mission_1704567890123")
    val requestId: String,                   // Original request ID for traceability
    val intent: OrionIntentIntelligence.Intent,
    val decision: OrionDecisionEngine.Decision,
    val plan: OrionPlanningEngine.ExecutionPlan?,
    val status: OrionMissionStatus,
    val checkpointJson: String? = null,      // Serializable runtime state for resume
    val specialistReceipts: List<OrionSpecialistReceipt> = emptyList(),
    val evidence: List<OrionTaskOutcome> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val failureReason: String? = null,
    val recoveryPlan: String? = null,        // JSON of replanned ExecutionPlan
    val parentMissionId: String? = null      // For nested/specialist missions
) {

    fun withStatus(
        newStatus: OrionMissionStatus,
        checkpointJson: String? = this.checkpointJson,
        failureReason: String? = this.failureReason,
        completedAt: Long? = this.completedAt
    ): OrionMission {
        return copy(
            status = newStatus,
            checkpointJson = checkpointJson,
            failureReason = failureReason,
            completedAt = completedAt,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun withReceipt(receipt: OrionSpecialistReceipt): OrionMission {
        return copy(
            specialistReceipts = specialistReceipts + receipt,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun withEvidence(outcome: OrionTaskOutcome): OrionMission {
        return copy(
            evidence = evidence + outcome,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun withPlan(plan: OrionPlanningEngine.ExecutionPlan): OrionMission {
        return copy(
            plan = plan,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun withRecoveryPlan(plan: OrionPlanningEngine.ExecutionPlan): OrionMission {
        return copy(
            recoveryPlan = Json.encodeToString(plan),
            updatedAt = System.currentTimeMillis()
        )
    }

    fun isTerminal(): Boolean {
        return status in setOf(
            OrionMissionStatus.COMPLETED,
            OrionMissionStatus.FAILED,
            OrionMissionStatus.CANCELLED
        )
    }

    fun isActive(): Boolean {
        return status in setOf(
            OrionMissionStatus.INTAKE,
            OrionMissionStatus.PLANNED,
            OrionMissionStatus.EXECUTING,
            OrionMissionStatus.VERIFYING,
            OrionMissionStatus.SUSPENDED,
            OrionMissionStatus.CHECKPOINTED
        )
    }

    fun canResume(): Boolean {
        return status in setOf(
            OrionMissionStatus.SUSPENDED,
            OrionMissionStatus.CHECKPOINTED,
            OrionMissionStatus.FAILED
        )
    }

    companion object {
        fun newMission(
            requestId: String,
            intent: OrionIntentIntelligence.Intent,
            decision: OrionDecisionEngine.Decision
        ): OrionMission {
            return OrionMission(
                id = "mission_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}",
                requestId = requestId,
                intent = intent,
                decision = decision,
                plan = decision.executionPlan,
                status = if (decision.canProceed) OrionMissionStatus.PLANNED else OrionMissionStatus.INTAKE
            )
        }
    }
}
