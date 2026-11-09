package com.phoneai.app

import kotlinx.serialization.Serializable

/**
 * Orion Decision Engine
 *
 * Commits to a strategy for an objective and records why. Not a formality layer:
 *  1. it runs the reasoning engine to enumerate candidates with computed scores;
 *  2. it applies policy (OrionPolicyEngine) to each candidate's required tools, which is where
 *     Trading Desk approval and blocked capabilities actually filter the option set;
 *  3. it selects the highest-scoring strategy that survives policy;
 *  4. it builds the executable plan for the winner and records every rejected alternative;
 *  5. it decides, with the intent's real constraints, whether the owner must confirm first.
 */
internal class OrionDecisionEngine(
    private val svc: PhoneAiService
) {

    @Serializable
    data class RejectedAlternative(
        val strategyId: String,
        val name: String,
        val reason: String,
        val confidence: Double
    )

    @Serializable
    data class PolicyCheck(
        val tool: String,
        val verdict: OrionPolicyEngine.Verdict,
        val reason: String
    )

    @Serializable
    data class Decision(
        val decisionId: String,
        val timestamp: Long,
        val intent: OrionIntentIntelligence.Intent,
        val reasoningResult: OrionReasoningEngine.ReasoningResult,
        val selectedStrategy: OrionReasoningEngine.Strategy?,
        val executionPlan: OrionPlanningEngine.ExecutionPlan?,
        val requiresConfirmation: Boolean,
        val confirmationReason: String?,
        val rationale: String,
        val alternativesRejected: List<RejectedAlternative>,
        val policyChecks: List<PolicyCheck> = emptyList(),
        val canProceed: Boolean = true,
        val blocker: String? = null
    )

    fun decide(
        intent: OrionIntentIntelligence.Intent,
        context: OrionContextIntegration.UnifiedContext?,
        reasoningEngine: OrionReasoningEngine,
        planningEngine: OrionPlanningEngine,
        policyEngine: OrionPolicyEngine
    ): Decision {
        val reasoning = reasoningEngine.reason(intent, context)

        // ---- policy gate on each candidate's tools ----
        val checks = mutableListOf<PolicyCheck>()
        val reasonToToolChecks = reasoning.strategies.associate { strategy ->
            val results = strategy.prerequisites.map { tool ->
                val evaluation = policyEngine.evaluateCapability(tool, context)
                checks += PolicyCheck(tool, evaluation.verdict, evaluation.reason ?: "")
                evaluation
            }
            strategy.id to results
        }

        val policyDenied = reasonToToolChecks.filterValues { evals ->
            evals.any { it.verdict == OrionPolicyEngine.Verdict.DENY }
        }.keys
        val policyAsk = reasonToToolChecks.filterValues { evals ->
            evals.any { it.verdict == OrionPolicyEngine.Verdict.ASK }
        }.keys

        // ---- selection: highest confidence among feasible, policy-clean candidates ----
        val eligible = reasoning.strategies
            .filter { it.feasible }
            .filterNot { it.id in policyDenied }
        val selected = eligible.maxByOrNull { it.confidence }

        val rejected = reasoning.strategies
            .filter { it.id != selected?.id }
            .map { s ->
                val reason = when {
                    !s.feasible -> "unmet prerequisite: " + s.unmetPrerequisites.joinToString(", ")
                    s.id in policyDenied -> "denied by policy"
                    selected != null -> "lower score (%.2f vs %.2f)".format(s.confidence, selected.confidence)
                    else -> "not selected"
                }
                RejectedAlternative(s.id, s.name, reason, s.confidence)
            }
            .sortedByDescending { it.confidence }

        if (selected == null) {
            return Decision(
                decisionId = "decision_" + System.currentTimeMillis(),
                timestamp = System.currentTimeMillis(),
                intent = intent,
                reasoningResult = reasoning,
                selectedStrategy = null,
                executionPlan = null,
                requiresConfirmation = false,
                confirmationReason = null,
                rationale = "No feasible strategy: every candidate has an unmet prerequisite or is policy-denied. " +
                    "Blockers: " + rejected.take(4).joinToString("; ") { "${it.strategyId}: ${it.reason}" },
                alternativesRejected = rejected,
                policyChecks = checks,
                canProceed = false,
                blocker = rejected.firstOrNull()?.reason
            )
        }

        // ---- confirmation: derived from intent constraints and tools, not from string matching ----
        val (requiresConfirmation, confirmationReason) = confirmationFor(intent, selected, policyAsk)

        val plan = planningEngine.createPlan(intent.primaryObjective, intent, selected, context)

        val rationale = buildRationale(intent, reasoning, selected, plan, rejected, checks, requiresConfirmation, confirmationReason)

        return Decision(
            decisionId = "decision_" + System.currentTimeMillis(),
            timestamp = System.currentTimeMillis(),
            intent = intent,
            reasoningResult = reasoning,
            selectedStrategy = selected,
            executionPlan = plan,
            requiresConfirmation = requiresConfirmation,
            confirmationReason = confirmationReason,
            rationale = rationale,
            alternativesRejected = rejected,
            policyChecks = checks,
            canProceed = true
        )
    }

    /**
     * Confirmation is required only where the platform genuinely refuses silent execution:
     * Trading Desk, explicit financial intent, irreversible device control, or a policy ASK.
     * Ordinary assistant work never asks twice.
     */
    private fun confirmationFor(
        intent: OrionIntentIntelligence.Intent,
        strategy: OrionReasoningEngine.Strategy,
        policyAsk: Set<String>
    ): Pair<Boolean, String?> {
        if (intent.constraints.any { it.type == "financial" }) {
            return true to "Trading Desk action: explicit Boss approval required"
        }
        if (strategy.prerequisites.any { it in TRADING_TOOLS }) {
            return true to "Trading Desk tool selected; explicit Boss approval required"
        }
        if (intent.type == OrionIntentIntelligence.IntentType.SYSTEM_CONTROL) {
            return true to "Irreversible device control (restart/shutdown/radio) requires confirmation"
        }
        if (strategy.id in policyAsk) {
            return true to "Policy requires acknowledgement for this tool set"
        }
        return false to null
    }

    private fun buildRationale(
        intent: OrionIntentIntelligence.Intent,
        reasoning: OrionReasoningEngine.ReasoningResult,
        selected: OrionReasoningEngine.Strategy,
        plan: OrionPlanningEngine.ExecutionPlan,
        rejected: List<RejectedAlternative>,
        checks: List<PolicyCheck>,
        needsConfirm: Boolean,
        confirmReason: String?
    ): String = buildString {
        appendLine("DECISION ${selected.id}${"_"}${System.currentTimeMillis()}")
        appendLine("objective: ${intent.primaryObjective.take(200)}")
        appendLine("intent: ${intent.type} (confidence ${"%.2f".format(intent.confidence)})")
        appendLine("understood: ${intent.rationale}")
        appendLine("chosen: ${selected.name} — score ${"%.2f".format(selected.confidence)}")
        selected.scoreReasons.forEach { appendLine("  + $it") }
        appendLine("risk: ${reasoning.riskAssessment.overallRisk}")
        reasoning.riskAssessment.riskFactors.forEach { appendLine("  ! ${it.description}") }
        if (checks.isNotEmpty()) {
            appendLine("policy: " + checks.joinToString("; ") { "${it.tool}=${it.verdict}" })
        }
        appendLine("plan: ${plan.steps.size} steps via ${plan.strategyId}")
        plan.steps.forEach { s ->
            val dep = if (s.dependsOn.isEmpty()) "" else " after ${s.dependsOn.joinToString(",")}"
            appendLine("  ${s.stepId} ${s.tool}${dep} — verify: ${s.verificationMethod}")
        }
        if (rejected.isNotEmpty()) {
            appendLine("rejected: " + rejected.take(4).joinToString("; ") { "${it.strategyId} (${it.reason})" })
        }
        if (needsConfirm) appendLine("CONFIRM REQUIRED: $confirmReason")
        append("END DECISION")
    }

    private companion object {
        val TRADING_TOOLS = setOf("trading_place_order", "trading_order", "place_order")
    }
}