package com.phoneai.app

import kotlinx.serialization.Serializable

/**
 * Orion Reasoning Engine
 *
 * Generates candidate strategies for an objective and ranks them against live evidence:
 * what capabilities are actually eligible right now, what Cerebro remembers about similar
 * past attempts, what the device state allows, and what the intent constraints forbid.
 *
 * This is where "which approach should I take" is decided with numbers, not vibes. Every
 * score contribution is recorded so the decision log can explain the ranking.
 */
internal class OrionReasoningEngine(
    private val svc: PhoneAiService
) {

    @Serializable
    enum class RiskLevel { LOW, MEDIUM, HIGH }
    @Serializable
    enum class RiskImpact { LOW, MEDIUM, HIGH, CATASTROPHIC }

    @Serializable
    data class RiskFactor(
        val description: String,
        val impact: RiskImpact
    )

    @Serializable
    data class RiskAssessment(
        val overallRisk: RiskImpact,
        val riskFactors: List<RiskFactor>
    )

    /** A named approach. Scores are computed, and `scoreReasons` says why. */
    @Serializable
    data class Strategy(
        val id: String,
        val name: String,
        val confidence: Double,
        val riskLevel: RiskLevel,
        val estimatedDurationMs: Long,
        val estimatedSteps: Int,
        val prerequisites: List<String>,
        val satisfiedPrerequisites: List<String>,
        val unmetPrerequisites: List<String>,
        val fallbackStrategies: List<String>,
        val scoreReasons: List<String>
    ) {
        val feasible: Boolean get() = unmetPrerequisites.isEmpty()
    }

    @Serializable
    data class ReasoningResult(
        val strategies: List<Strategy>,
        val selectedStrategy: Strategy?,
        val riskAssessment: RiskAssessment
    )

    fun reason(
        intent: OrionIntentIntelligence.Intent,
        context: OrionContextIntegration.UnifiedContext?
    ): ReasoningResult {
        val capabilities = context?.capabilities
        val memory = context?.memory
        val device = context?.device

        val candidates = mutableListOf<Strategy>()

        // ---- Candidate 1: direct single-tool execution ----
        val directPrereqs = intent.requiredCapabilities
        val directSatisfied = directPrereqs.filter { capabilities?.isAvailable(it) == true }
        val directUnmet = directPrereqs.filterNot { capabilities?.isAvailable(it) == true }
            .filter { capabilities != null } // null context means unknown, not missing
        candidates.add(Strategy(
            id = "direct_tool",
            name = "Direct tool execution",
            confidence = score(
                base = 0.55,
                intent = intent,
                satisfied = directSatisfied.size,
                total = directPrereqs.size,
                memory = memory,
                toolHint = directPrereqs.firstOrNull()
            ),
            riskLevel = RiskLevel.LOW,
            estimatedDurationMs = 6_000,
            estimatedSteps = 1,
            prerequisites = directPrereqs,
            satisfiedPrerequisites = directSatisfied,
            unmetPrerequisites = directUnmet,
            fallbackStrategies = listOf("sequential_steps", "inspect_then_act"),
            scoreReasons = listOf(
                "single dispatch satisfies ${directSatisfied.size}/${directPrereqs.size} prerequisites"
            )
        ))

        // ---- Candidate 2: inspect → act → verify sequence ----
        val seqPrereqs = (intent.requiredCapabilities + "read_screen").distinct()
        val seqSatisfied = seqPrereqs.filter { capabilities?.isAvailable(it) == true }
        val seqUnmet = seqPrereqs.filterNot { capabilities?.isAvailable(it) == true }.filter { capabilities != null }
        candidates.add(Strategy(
            id = "inspect_then_act",
            name = "Inspect state, act, then verify",
            confidence = score(
                base = 0.60,
                intent = intent,
                satisfied = seqSatisfied.size,
                total = seqPrereqs.size,
                memory = memory,
                toolHint = seqPrereqs.firstOrNull()
            ),
            riskLevel = RiskLevel.LOW,
            estimatedDurationMs = 18_000,
            estimatedSteps = 3,
            prerequisites = seqPrereqs,
            satisfiedPrerequisites = seqSatisfied,
            unmetPrerequisites = seqUnmet,
            fallbackStrategies = listOf("sequential_steps"),
            scoreReasons = listOf("grounds each action in fresh screen evidence before acting")
        ))

        // ---- Candidate 3: multi-step sequential plan ----
        val stepEstimate = estimateSteps(intent)
        candidates.add(Strategy(
            id = "sequential_steps",
            name = "Sequential multi-step plan",
            confidence = score(
                base = if (intent.type == OrionIntentIntelligence.IntentType.MULTI_STEP) 0.72 else 0.45,
                intent = intent,
                satisfied = seqSatisfied.size,
                total = seqPrereqs.size,
                memory = memory,
                toolHint = null
            ),
            riskLevel = RiskLevel.MEDIUM,
            estimatedDurationMs = stepEstimate * 7_000L,
            estimatedSteps = stepEstimate,
            prerequisites = seqPrereqs,
            satisfiedPrerequisites = seqSatisfied,
            unmetPrerequisites = seqUnmet,
            fallbackStrategies = listOf("inspect_then_act"),
            scoreReasons = listOf("intent decomposed into $stepEstimate dependent steps")
        ))

        // ---- Candidate 4: research-first then act ----
        val researchAvailable = capabilities?.isAvailable("browser_navigate") == true
        candidates.add(Strategy(
            id = "research_first",
            name = "Research first, then act on findings",
            confidence = score(
                base = if (intent.type == OrionIntentIntelligence.IntentType.RESEARCH) 0.75 else 0.30,
                intent = intent,
                satisfied = if (researchAvailable) 1 else 0,
                total = 1,
                memory = memory,
                toolHint = "browser_navigate"
            ),
            riskLevel = RiskLevel.LOW,
            estimatedDurationMs = 25_000,
            estimatedSteps = 2,
            prerequisites = listOf("browser_navigate", "browser_snapshot"),
            satisfiedPrerequisites = if (researchAvailable) listOf("browser_navigate", "browser_snapshot") else emptyList(),
            unmetPrerequisites = if (!researchAvailable && capabilities != null) listOf("browser_navigate") else emptyList(),
            fallbackStrategies = listOf("direct_tool"),
            scoreReasons = listOf("web evidence reduces wrong-target risk")
        ))

        // ---- Candidate 5: specialist delegation ----
        val delegateAvailable = capabilities?.isAvailable("ask_agent") == true
        candidates.add(Strategy(
            id = "parallel_delegation",
            name = "Delegate to specialist agent",
            confidence = score(
                base = if (intent.type == OrionIntentIntelligence.IntentType.DELEGATION) 0.78 else 0.35,
                intent = intent,
                satisfied = if (delegateAvailable) 1 else 0,
                total = 1,
                memory = memory,
                toolHint = "ask_agent"
            ),
            riskLevel = RiskLevel.MEDIUM,
            estimatedDurationMs = 40_000,
            estimatedSteps = 2,
            prerequisites = listOf("ask_agent"),
            satisfiedPrerequisites = if (delegateAvailable) listOf("ask_agent") else emptyList(),
            unmetPrerequisites = if (!delegateAvailable && capabilities != null) listOf("ask_agent") else emptyList(),
            fallbackStrategies = listOf("sequential_steps"),
            scoreReasons = listOf("parent keeps ownership; specialist returns a receipt")
        ))

        // ---- Candidate 6: python / code execution ----
        val pythonAvailable = capabilities?.isAvailable("python_exec") == true
        candidates.add(Strategy(
            id = "code_execution",
            name = "Write and run code in the sandbox",
            confidence = score(
                base = if (intent.type == OrionIntentIntelligence.IntentType.CREATIVE) 0.76 else 0.25,
                intent = intent,
                satisfied = if (pythonAvailable) 1 else 0,
                total = 1,
                memory = memory,
                toolHint = "python_exec"
            ),
            riskLevel = RiskLevel.MEDIUM,
            estimatedDurationMs = 20_000,
            estimatedSteps = 2,
            prerequisites = listOf("python_exec"),
            satisfiedPrerequisites = if (pythonAvailable) listOf("python_exec") else emptyList(),
            unmetPrerequisites = if (!pythonAvailable && capabilities != null) listOf("python_exec") else emptyList(),
            fallbackStrategies = listOf("direct_tool"),
            scoreReasons = listOf("deterministic computation beats guessing")
        ))

        // ---- Candidate 7: conversational answer (no tools) ----
        candidates.add(Strategy(
            id = "answer_directly",
            name = "Answer from knowledge and memory",
            confidence = score(
                base = if (intent.type == OrionIntentIntelligence.IntentType.QUESTION) 0.82 else 0.20,
                intent = intent,
                satisfied = 0,
                total = 0,
                memory = memory,
                toolHint = null
            ),
            riskLevel = RiskLevel.LOW,
            estimatedDurationMs = 3_000,
            estimatedSteps = 1,
            prerequisites = emptyList(),
            satisfiedPrerequisites = emptyList(),
            unmetPrerequisites = emptyList(),
            fallbackStrategies = emptyList(),
            scoreReasons = listOf("no device mutation; nothing to verify beyond the answer")
        ))

        // ---- Candidate 8: clarify (only when genuinely ambiguous) ----
        candidates.add(Strategy(
            id = "clarify_first",
            name = "Ask one clarifying question",
            confidence = if (intent.type == OrionIntentIntelligence.IntentType.UNKNOWN) 0.70 else 0.10,
            riskLevel = RiskLevel.LOW,
            estimatedDurationMs = 2_000,
            estimatedSteps = 1,
            prerequisites = emptyList(),
            satisfiedPrerequisites = emptyList(),
            unmetPrerequisites = emptyList(),
            fallbackStrategies = listOf("answer_directly"),
            scoreReasons = listOf(if (intent.type == OrionIntentIntelligence.IntentType.UNKNOWN)
                "intent could not be classified from the request" else "not needed")
        ))

        // ---- Device penalties are real, not decorative ----
        val adjusted = candidates.map { s ->
            var c = s.confidence
            val reasons = s.scoreReasons.toMutableList()
            if (device != null) {
                val needsUi = s.prerequisites.any { it in UI_TOOLS }
                if (needsUi && device.screenState == OrionContextIntegration.DeviceContext.ScreenState.OFF) {
                    c -= 0.15
                    reasons += "screen is off; UI strategies are less likely to succeed"
                }
                if (needsUi && device.batteryLevel != null && device.batteryLevel < 10 && !device.isCharging) {
                    c -= 0.05
                    reasons += "battery critically low"
                }
                if (device.networkType == null &&
                    s.prerequisites.any { it.startsWith("browser_") || it == "ask_agent" }
                ) {
                    c -= 0.30
                    reasons += "no network; web-dependent strategy degraded"
                }
            }
            s.copy(confidence = c.coerceIn(0.0, 0.98), scoreReasons = reasons)
        }

        // Infeasible strategies keep their score for the log but are never selected.
        val selected = adjusted
            .filter { it.feasible && it.id != "clarify_first" || (it.feasible && intent.type == OrionIntentIntelligence.IntentType.UNKNOWN) }
            .maxByOrNull { it.confidence }

        val riskFactors = mutableListOf<RiskFactor>()
        if (intent.constraints.any { it.severity == "CRITICAL" }) {
            riskFactors.add(RiskFactor("Critical constraint (financial approval required)", RiskImpact.HIGH))
        }
        if (intent.constraints.any { it.severity == "HIGH" }) {
            riskFactors.add(RiskFactor("High severity constraint present", RiskImpact.MEDIUM))
        }
        if (adjusted.all { it.unmetPrerequisites.isNotEmpty() }) {
            riskFactors.add(RiskFactor("Every candidate strategy has an unmet prerequisite", RiskImpact.HIGH))
        }
        if (selected != null && selected.estimatedSteps >= 4) {
            riskFactors.add(RiskFactor("Long plan; later steps can drift from the objective", RiskImpact.MEDIUM))
        }
        val overallRisk = when {
            riskFactors.any { it.impact == RiskImpact.CATASTROPHIC } -> RiskImpact.CATASTROPHIC
            riskFactors.any { it.impact == RiskImpact.HIGH } -> RiskImpact.HIGH
            riskFactors.isNotEmpty() -> RiskImpact.MEDIUM
            else -> RiskImpact.LOW
        }

        return ReasoningResult(adjusted, selected, RiskAssessment(overallRisk, riskFactors))
    }

    /**
     * Real scoring. Evidence raises confidence; missing prerequisites, unproven tools and a
     * history of failures on the same objective lower it.
     */
    private fun score(
        base: Double,
        intent: OrionIntentIntelligence.Intent,
        satisfied: Int,
        total: Int,
        memory: OrionContextIntegration.MemoryContext?,
        toolHint: String?
    ): Double {
        var s = base
        if (total > 0) s += 0.25 * (satisfied.toDouble() / total)
        s += (intent.confidence - 0.5) * 0.2
        if (memory != null) {
            if (memory.recalledMemories.isNotEmpty()) s += 0.05
            if (toolHint != null && memory.recentSuccesses.any { it.startsWith(toolHint) }) s += 0.10
            if (toolHint != null && memory.recentFailures.any { it.startsWith(toolHint) }) s -= 0.12
            if (memory.experiencePatterns.isNotEmpty()) s += 0.03
        }
        return s.coerceIn(0.0, 0.98)
    }

    /** Step estimate from real operation count in the request, not a fixed table. */
    private fun estimateSteps(intent: OrionIntentIntelligence.Intent): Int {
        val ops = Regex("(?i)\\b(open|launch|search|click|tap|type|send|copy|save|install|download|call|verify)\\b")
            .findAll(intent.primaryObjective).map { it.value.lowercase() }.distinct().count()
        return when {
            ops >= 4 -> 5
            ops == 3 -> 4
            ops == 2 -> 3
            intent.type == OrionIntentIntelligence.IntentType.MULTI_STEP -> 3
            else -> 2
        }
    }

    private companion object {
        val UI_TOOLS = setOf("open_app", "click", "type_text", "swipe", "read_screen", "send_message")
    }
}