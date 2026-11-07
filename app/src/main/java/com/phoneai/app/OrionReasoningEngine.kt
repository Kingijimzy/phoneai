package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Orion Reasoning Engine
 *
 * Evaluates possible approaches, constraints, capabilities, and risks for a given intent.
 * Produces a ranked list of strategies with trade-off analysis.
 */
class OrionReasoningEngine(
    private val svc: PhoneAiService,
    private val capabilityDiscovery: OrionCapabilityDiscovery,
    private val taskOrchestrator: OrionTaskOrchestrator,
    private val recoveryPlanner: OrionRecoveryPlanner,
    private val proactiveEngine: OrionProactiveEngine,
    private val recall: OrionRecall
) {

    data class Strategy(
        val id: String,
        val name: String,
        val description: String,
        val approach: Approach,
        val requiredCapabilities: Set<String>,
        val estimatedSteps: Int,
        val estimatedDurationMs: Long,
        val riskLevel: RiskLevel,
        val confidence: Double,
        val pros: List<String>,
        val cons: List<String>,
        val fallbackStrategies: List<String>,
        val prerequisites: List<Prerequisite>
    ) {
        enum class Approach {
            DIRECT_TOOL,           // Single tool call
            SEQUENTIAL_STEPS,      // Multiple dependent steps
            PARALLEL_DELEGATION,   // Multiple agents in parallel
            RESEARCH_THEN_ACT,     // Research first, then execute
            CODE_EXECUTION,        // Write and run code
            BROWSER_AUTOMATION,    // Web interaction
            SKILL_INVOCATION,      // Use registered skill
            MACRO_EXECUTION,       // Run saved macro
            HYBRID                 // Combination of above
        }

        enum class RiskLevel { MINIMAL, LOW, MEDIUM, HIGH, CRITICAL }
    }

    data class Prerequisite(
        val type: PrerequisiteType,
        val description: String,
        val satisfied: Boolean,
        val resolutionHint: String?
    ) {
        enum class PrerequisiteType {
            CAPABILITY,      // Tool/skill availability
            PERMISSION,      // Android permission or user approval
            STATE,           // Device/app state
            DATA,            // Required information
            RESOURCE,        // Battery, network, quota
            DEPENDENCY       // Other task completion
        }
    }

    data class ReasoningResult(
        val intent: OrionIntentIntelligence.Intent,
        val strategies: List<Strategy>,
        val selectedStrategy: Strategy?,
        val decisionRationale: String,
        val riskAssessment: RiskAssessment,
        val alternativeConsidered: List<Strategy>,
        val contextUsed: ContextSummary
    )

    data class RiskAssessment(
        val overallRisk: Strategy.RiskLevel,
        val riskFactors: List<RiskFactor>,
        val mitigationStrategies: List<String>,
        val requiresConfirmation: Boolean,
        val confirmationReason: String?
    )

    data class RiskFactor(
        val category: String,
        val description: String,
        val likelihood: Likelihood,
        val impact: Impact
    ) {
        enum class Likelihood { RARE, UNLIKELY, POSSIBLE, LIKELY, ALMOST_CERTAIN }
        enum class Impact { NEGLIGIBLE, MINOR, MODERATE, MAJOR, CATASTROPHIC }
    }

    data class ContextSummary(
        val memoryRecalled: Int,
        val deviceState: String,
        val activeObjectives: Int,
        val availableCapabilities: Int,
        val recentFailures: Int,
        val relevantSkills: Int
    )

    /**
     * Reason about the best approach for the given intent.
     */
    fun reason(intent: OrionIntentIntelligence.Intent): ReasoningResult {
        // 1. Gather context
        val context = gatherContext(intent)

        // 2. Generate candidate strategies
        val candidates = generateStrategies(intent, context)

        // 3. Evaluate each strategy
        val evaluated = candidates.map { evaluateStrategy(it, intent, context) }

        // 4. Rank strategies
        val ranked = rankStrategies(evaluated, intent)

        // 5. Select best strategy
        val selected = ranked.firstOrNull()

        // 6. Assess risks
        val riskAssessment = assessRisks(selected, intent, context)

        // 7. Build decision rationale
        val rationale = buildRationale(selected, ranked, intent, riskAssessment)

        return ReasoningResult(
            intent = intent,
            strategies = ranked,
            selectedStrategy = selected,
            decisionRationale = rationale,
            riskAssessment = riskAssessment,
            alternativeConsidered = ranked.drop(1).take(3),
            contextUsed = context
        )
    }

    private fun gatherContext(intent: OrionIntentIntelligence.Intent): ContextSummary {
        // Recall relevant memories
        val memories = recall.recall(intent.primaryObjective, maxResults = 5)
        val memoryCount = memories.size

        // Get device state
        val deviceContext = OrionDeviceContextProvider.summary(svc)
        val deviceState = "battery=${deviceContext?.batteryLevel ?: "?"}%, " +
            "charging=${deviceContext?.isCharging ?: "?"}, " +
            "network=${deviceContext?.networkType ?: "?"}"

        // Count active objectives
        val activeGraph = OrionObjectiveGraph.load(svc)
        val activeObjectives = activeGraph?.nodes?.count { it.status == OrionObjectiveGraph.NodeStatus.IN_PROGRESS } ?: 0

        // Available capabilities
        val capabilities = capabilityDiscovery.getAvailableCapabilities(svc)
        val availableCapabilities = capabilities.size

        // Recent failures
        val recentFailures = recall.getRecentFailures(limit = 10).size

        // Relevant skills
        val skills = OrionSkillsCapabilityExecutor.discover(svc, intent.primaryObjective, 5)
        val relevantSkills = skills.size

        return ContextSummary(
            memoryRecalled = memoryCount,
            deviceState = deviceState,
            activeObjectives = activeObjectives,
            availableCapabilities = availableCapabilities,
            recentFailures = recentFailures,
            relevantSkills = relevantSkills
        )
    }

    private fun generateStrategies(
        intent: OrionIntentIntelligence.Intent,
        context: ContextSummary
    ): List<Strategy> {
        val strategies = mutableListOf<Strategy>()

        // Strategy 1: Direct tool execution (for simple actions)
        if (intent.type == OrionIntentIntelligence.IntentType.ACTION) {
            val requiredCaps = inferRequiredCapabilities(intent)
            val available = requiredCaps.filter { it in capabilityDiscovery.getAvailableCapabilities(svc).map { it.name } }
            if (available.size == requiredCaps.size) {
                strategies.add(Strategy(
                    id = "direct_tool",
                    name = "Direct Tool Execution",
                    description = "Execute the most appropriate single tool for the requested action",
                    approach = Strategy.Approach.DIRECT_TOOL,
                    requiredCapabilities = requiredCaps,
                    estimatedSteps = 1,
                    estimatedDurationMs = 2000,
                    riskLevel = Strategy.RiskLevel.LOW,
                    confidence = 0.9,
                    pros = listOf("Fast", "Simple", "Minimal overhead"),
                    cons = listOf("Only works for single-step actions"),
                    fallbackStrategies = listOf("sequential_steps", "research_then_act"),
                    prerequisites = requiredCaps.map { cap ->
                        Prerequisite(Prerequisite.PrerequisiteType.CAPABILITY, "Capability $cap available", available.contains(cap), if (!available.contains(cap)) "Install or enable $cap" else null)
                    }
                ))
            }
        }

        // Strategy 2: Sequential steps (for multi-step tasks)
        if (intent.type == OrionIntentIntelligence.IntentType.MULTI_STEP_TASK ||
            intent.type == OrionIntentIntelligence.IntentType.CODING ||
            intent.type == OrionIntentIntelligence.IntentType.AUTOMATION) {
            val requiredCaps = inferRequiredCapabilities(intent) + setOf("task_add", "objective_graph")
            strategies.add(Strategy(
                id = "sequential_steps",
                name = "Sequential Step Execution",
                description = "Break objective into dependent steps, execute in order with verification",
                approach = Strategy.Approach.SEQUENTIAL_STEPS,
                requiredCapabilities = requiredCaps,
                estimatedSteps = maxOf(3, intent.constraints.size + 2),
                estimatedDurationMs = 30000L,
                riskLevel = Strategy.RiskLevel.MEDIUM,
                confidence = 0.8,
                pros = listOf("Handles dependencies", "Verification at each step", "Recoverable"),
                cons = listOf("Slower", "More complex", "Multiple failure points"),
                fallbackStrategies = listOf("parallel_delegation", "macro_execution"),
                prerequisites = requiredCaps.map { cap ->
                    Prerequisite(Prerequisite.PrerequisiteType.CAPABILITY, "Capability $cap available", cap in capabilityDiscovery.getAvailableCapabilities(svc).map { it.name }, null)
                }
            ))
        }

        // Strategy 3: Parallel delegation (for independent subtasks)
        if (intent.type == OrionIntentIntelligence.IntentType.RESEARCH ||
            intent.entities.values.any { it == "RESEARCH" }) {
            strategies.add(Strategy(
                id = "parallel_delegation",
                name = "Parallel Specialist Delegation",
                description = "Create multiple specialist agents for independent research subtasks",
                approach = Strategy.Approach.PARALLEL_DELEGATION,
                requiredCapabilities = setOf("ask_agent", "queue_agent", "web_search"),
                estimatedSteps = 2,
                estimatedDurationMs = 45000L,
                riskLevel = Strategy.RiskLevel.MEDIUM,
                confidence = 0.75,
                pros = listOf("Fast for independent tasks", "Specialized expertise", "Parallel execution"),
                cons = listOf("Coordination overhead", "Result integration needed", "Higher resource use"),
                fallbackStrategies = listOf("sequential_steps", "research_then_act"),
                prerequisites = listOf(
                    Prerequisite(Prerequisite.PrerequisiteType.CAPABILITY, "Agent delegation available", true, null),
                    Prerequisite(Prerequisite.PrerequisiteType.RESOURCE, "Sufficient API quota", context.availableCapabilities > 5, null)
                )
            ))
        }

        // Strategy 4: Research then act (for ambiguous or info-heavy tasks)
        if (intent.type == OrionIntentIntelligence.IntentType.RESEARCH ||
            intent.confidence < 0.6 ||
            intent.constraints.any { it.type == OrionIntentIntelligence.Constraint.ConstraintType.CAPABILITY }) {
            strategies.add(Strategy(
                id = "research_then_act",
                name = "Research Then Act",
                description = "Gather information first, then plan and execute based on findings",
                approach = Strategy.Approach.RESEARCH_THEN_ACT,
                requiredCapabilities = setOf("web_search", "fetch_url", "browser_navigate") + inferRequiredCapabilities(intent),
                estimatedSteps = 3,
                estimatedDurationMs = 60000L,
                riskLevel = Strategy.RiskLevel.LOW,
                confidence = 0.7,
                pros = listOf("Informed decisions", "Reduces capability gaps", "Handles ambiguity"),
                cons = listOf("Slowest approach", "Multiple rounds", "May over-research"),
                fallbackStrategies = listOf("sequential_steps", "direct_tool"),
                prerequisites = listOf(
                    Prerequisite(Prerequisite.PrerequisiteType.CAPABILITY, "Web research available", true, null),
                    Prerequisite(Prerequisite.PrerequisiteType.RESOURCE, "Network connectivity", true, null)
                )
            ))
        }

        // Strategy 5: Code execution (for coding tasks)
        if (intent.type == OrionIntentIntelligence.IntentType.CODING) {
            strategies.add(Strategy(
                id = "code_execution",
                name = "Code Writing and Execution",
                description = "Write code in Python sandbox, execute, verify output, iterate on errors",
                approach = Strategy.Approach.CODE_EXECUTION,
                requiredCapabilities = setOf("python_exec", "shell", "file_write", "file_read"),
                estimatedSteps = 4,
                estimatedDurationMs = 45000L,
                riskLevel = Strategy.RiskLevel.MEDIUM,
                confidence = 0.85,
                pros = listOf("Verifiable output", "Iterative debugging", "Complex logic support"),
                cons = listOf("Requires Python environment", "Execution time", "Sandbox limitations"),
                fallbackStrategies = listOf("sequential_steps", "direct_tool"),
                prerequisites = listOf(
                    Prerequisite(Prerequisite.PrerequisiteType.CAPABILITY, "Python execution available", true, null),
                    Prerequisite(Prerequisite.PrerequisiteType.RESOURCE, "Sandbox resources available", true, null)
                )
            ))
        }

        // Strategy 6: Browser automation
        if (intent.type == OrionIntentIntelligence.IntentType.AUTOMATION ||
            intent.entities.values.any { it == "URL" }) {
            strategies.add(Strategy(
                id = "browser_automation",
                name = "Browser Automation",
                description = "Navigate, interact, extract data from web pages using browser tools",
                approach = Strategy.Approach.BROWSER_AUTOMATION,
                requiredCapabilities = setOf("browser_navigate", "browser_click", "browser_type", "browser_snapshot", "fetch_url"),
                estimatedSteps = 5,
                estimatedDurationMs = 60000L,
                riskLevel = Strategy.RiskLevel.MEDIUM,
                confidence = 0.7,
                pros = listOf("Full web interaction", "JavaScript support", "Visual verification"),
                cons = listOf("Slow", "Fragile selectors", "Site changes break automation"),
                fallbackStrategies = listOf("research_then_act", "sequential_steps"),
                prerequisites = listOf(
                    Prerequisite(Prerequisite.PrerequisiteType.CAPABILITY, "Browser automation available", true, null),
                    Prerequisite(Prerequisite.PrerequisiteType.RESOURCE, "Network and display", true, null)
                )
            ))
        }

        // Strategy 7: Skill invocation (if relevant skill exists)
        val skills = OrionSkillsCapabilityExecutor.discover(svc, intent.primaryObjective, 3)
        if (skills.isNotEmpty()) {
            val skill = skills.first()
            strategies.add(Strategy(
                id = "skill_${skill.id}",
                name = "Skill Invocation: ${skill.name}",
                description = "Use registered skill: ${skill.description}",
                approach = Strategy.Approach.SKILL_INVOCATION,
                requiredCapabilities = setOf("skill_invoke"),
                estimatedSteps = 2,
                estimatedDurationMs = 10000L,
                riskLevel = Strategy.RiskLevel.LOW,
                confidence = 0.8,
                pros = listOf("Pre-verified capability", "Optimized for task", "Consistent results"),
                cons = listOf("Limited to skill scope", "May need parameters", "Skill may be outdated"),
                fallbackStrategies = listOf("sequential_steps", "direct_tool"),
                prerequisites = listOf(
                    Prerequisite(Prerequisite.PrerequisiteType.CAPABILITY, "Skill ${skill.id} available", true, null),
                    Prerequisite(Prerequisite.PrerequisiteType.DATA, "Required parameters provided", skill.parameters.isNotEmpty(), "Provide required parameters")
                )
            ))
        }

        // Strategy 8: Macro execution (if saved macro matches)
        val macros = OrionMacroExecutor.listMacros(svc)
        val matchingMacro = macros.find { it.name.lowercase().contains(intent.primaryObjective.lowercase().take(20)) }
        if (matchingMacro != null) {
            strategies.add(Strategy(
                id = "macro_${matchingMacro.id}",
                name = "Macro Execution: ${matchingMacro.name}",
                description = "Run saved macro: ${matchingMacro.description}",
                approach = Strategy.Approach.MACRO_EXECUTION,
                requiredCapabilities = setOf("macro_run"),
                estimatedSteps = matchingMacro.steps.size,
                estimatedDurationMs = matchingMacro.steps.size * 5000L,
                riskLevel = Strategy.RiskLevel.LOW,
                confidence = 0.9,
                pros = listOf("Pre-tested sequence", "Fast execution", "Consistent results"),
                cons = listOf("Fixed steps", "May not match exactly", "Requires maintenance"),
                fallbackStrategies = listOf("sequential_steps"),
                prerequisites = listOf(
                    Prerequisite(Prerequisite.PrerequisiteType.CAPABILITY, "Macro ${matchingMacro.id} available", true, null)
                )
            ))
        }

        // Strategy 9: Hybrid (combine approaches for complex tasks)
        if (intent.type == OrionIntentIntelligence.IntentType.MULTI_STEP_TASK && intent.constraints.size > 2) {
            strategies.add(Strategy(
                id = "hybrid",
                name = "Hybrid Approach",
                description = "Combine research, delegation, and sequential execution for complex multi-faceted tasks",
                approach = Strategy.Approach.HYBRID,
                requiredCapabilities = setOf("ask_agent", "web_search", "task_add", "python_exec", "objective_graph"),
                estimatedSteps = 6,
                estimatedDurationMs = 120000L,
                riskLevel = Strategy.RiskLevel.HIGH,
                confidence = 0.65,
                pros = listOf("Maximum flexibility", "Handles complex tasks", "Adaptive"),
                cons = listOf("Highest complexity", "Most failure points", "Resource intensive"),
                fallbackStrategies = listOf("sequential_steps", "research_then_act"),
                prerequisites = listOf(
                    Prerequisite(Prerequisite.PrerequisiteType.CAPABILITY, "Full capability suite needed", context.availableCapabilities > 10, "May need capability acquisition"),
                    Prerequisite(Prerequisite.PrerequisiteType.RESOURCE, "Extended execution budget", true, null)
                )
            ))
        }

        return strategies
    }

    private fun inferRequiredCapabilities(intent: OrionIntentIntelligence.Intent): Set<String> {
        val caps = mutableSetOf<String>()

        // From suggested capabilities
        caps.addAll(intent.suggestedCapabilities)

        // From intent type
        when (intent.type) {
            OrionIntentIntelligence.IntentType.CODING -> caps.addAll(setOf("python_exec", "shell"))
            OrionIntentIntelligence.IntentType.RESEARCH -> caps.addAll(setOf("web_search", "browser_navigate"))
            OrionIntentIntelligence.IntentType.AUTOMATION -> caps.addAll(setOf("open_app", "click", "browser_navigate"))
            OrionIntentIntelligence.IntentType.TRADING -> caps.addAll(setOf("trading_place_order", "trading_portfolio"))
            OrionIntentIntelligence.IntentType.SYSTEM_CONTROL -> caps.addAll(setOf("computer_settings_wifi_toggle"))
            OrionIntentIntelligence.IntentType.MEMORY_OP -> caps.addAll(setOf("memory"))
            OrionIntentIntelligence.IntentType.SCHEDULING -> caps.addAll(setOf("reminder_add"))
        }

        // From entities
        if (intent.entities.containsKey("URL")) caps.add("browser_navigate")
        if (intent.entities.containsKey("CRYPTO_SYMBOL")) caps.add("trading_ticker")

        return caps
    }

    private fun evaluateStrategy(
        strategy: Strategy,
        intent: OrionIntentIntelligence.Intent,
        context: ContextSummary
    ): Strategy {
        var confidence = strategy.confidence
        val pros = mutableListOf<String>().apply { addAll(strategy.pros) }
        val cons = mutableListOf<String>().apply { addAll(strategy.cons) }

        // Adjust confidence based on context
        // Missing capabilities reduce confidence
        val missingCaps = strategy.requiredCapabilities - capabilityDiscovery.getAvailableCapabilities(svc).map { it.name }
        if (missingCaps.isNotEmpty()) {
            confidence -= missingCaps.size * 0.15
            cons.add("${missingCaps.size} missing capabilities: ${missingCaps.joinToString(", ")}")
        }

        // Recent failures reduce confidence for similar approaches
        if (context.recentFailures > 3 && strategy.approach in setOf(Strategy.Approach.SEQUENTIAL_STEPS, Strategy.Approach.HYBRID)) {
            confidence -= 0.1
            cons.add("Recent failures suggest caution with complex approaches")
        }

        // Device constraints
        val batteryMatch = Regex("battery=(\\d+)").find(context.deviceState)
        batteryMatch?.let { match ->
            val battery = match.groupValues[1].toIntOrNull() ?: 100
            if (battery < 30 && strategy.estimatedDurationMs > 60000) {
                confidence -= 0.15
                cons.add("Low battery for long-running strategy")
            }
        }

        // Active objectives congestion
        if (context.activeObjectives > 3 && strategy.approach == Strategy.Approach.HYBRID) {
            confidence -= 0.1
            cons.add("High concurrent objective load")
        }

        // Available capabilities bonus
        if (context.availableCapabilities > 15) {
            confidence += 0.05
            pros.add("Rich capability environment")
        }

        // Memory recall bonus
        if (context.memoryRecalled > 0) {
            confidence += 0.05
            pros.add("${context.memoryRecalled} relevant memories recalled")
        }

        // Urgency adjustment
        if (intent.urgency == OrionIntentIntelligence.Urgency.CRITICAL && strategy.estimatedDurationMs > 30000) {
            confidence -= 0.2
            cons.add("Too slow for critical urgency")
        } else if (intent.urgency == OrionIntentIntelligence.Urgency.HIGH && strategy.approach == Strategy.Approach.DIRECT_TOOL) {
            confidence += 0.1
            pros.add("Fast execution for high urgency")
        }

        // Hard constraints penalty
        val hardConstraints = intent.constraints.count { it.severity == OrionIntentIntelligence.Constraint.Severity.HARD }
        if (hardConstraints > 2) {
            confidence -= hardConstraints * 0.05
            cons.add("$hardConstraints hard constraints to satisfy")
        }

        return strategy.copy(
            confidence = confidence.coerceIn(0.0, 1.0),
            pros = pros,
            cons = cons
        )
    }

    private fun rankStrategies(
        strategies: List<Strategy>,
        intent: OrionIntentIntelligence.Intent
    ): List<Strategy> {
        return strategies.sortedByDescending { strategy ->
            // Primary: confidence score
            var score = strategy.confidence * 100.0

            // Secondary: urgency alignment
            when (intent.urgency) {
                OrionIntentIntelligence.Urgency.CRITICAL -> {
                    if (strategy.estimatedDurationMs < 10000) score += 20
                    else if (strategy.estimatedDurationMs > 60000) score -= 30
                }
                OrionIntentIntelligence.Urgency.HIGH -> {
                    if (strategy.estimatedDurationMs < 30000) score += 10
                }
                OrionIntentIntelligence.Urgency.LOW -> {
                    if (strategy.approach == Strategy.Approach.RESEARCH_THEN_ACT) score += 5
                }
            }

            // Tertiary: risk preference (prefer lower risk)
            score -= when (strategy.riskLevel) {
                Strategy.RiskLevel.MINIMAL -> 0
                Strategy.RiskLevel.LOW -> 2
                Strategy.RiskLevel.MEDIUM -> 5
                Strategy.RiskLevel.HIGH -> 15
                Strategy.RiskLevel.CRITICAL -> 30
            }

            // Quaternary: fewer prerequisites
            val unsatisfiedPrereqs = strategy.prerequisites.count { !it.satisfied }
            score -= unsatisfiedPrereqs * 10

            score
        }
    }

    private fun assessRisks(
        selected: Strategy?,
        intent: OrionIntentIntelligence.Intent,
        context: ContextSummary
    ): RiskAssessment {
        val riskFactors = mutableListOf<RiskFactor>()
        val mitigations = mutableListOf<String>()

        val overallRisk = selected?.riskLevel ?: Strategy.RiskLevel.HIGH

        // Capability risks
        val missingCaps = selected?.requiredCapabilities?.minus(capabilityDiscovery.getAvailableCapabilities(svc).map { it.name }) ?: emptySet()
        if (missingCaps.isNotEmpty()) {
            riskFactors.add(RiskFactor("CAPABILITY", "Missing capabilities: ${missingCaps.joinToString(", ")}", RiskFactor.Likelihood.LIKELY, RiskFactor.Impact.MAJOR))
            mitigations.add("Acquire missing capabilities via Prometheus or use alternatives")
        }

        // Permission risks
        if (intent.type == OrionIntentIntelligence.IntentType.TRADING) {
            riskFactors.add(RiskFactor("PERMISSION", "Trading requires explicit user approval", RiskFactor.Likelihood.ALMOST_CERTAIN, RiskFactor.Impact.MAJOR))
            mitigations.add("Request user confirmation before any order placement")
        }
        if (intent.type == OrionIntentIntelligence.IntentType.SYSTEM_CONTROL) {
            riskFactors.add(RiskFactor("PERMISSION", "System settings may require Android permissions", RiskFactor.Likelihood.POSSIBLE, RiskFactor.Impact.MODERATE))
            mitigations.add("Check permissions before execution; fallback to user manual action")
        }

        // Security risks
        val securityConstraints = intent.constraints.filter { it.type == OrionIntentIntelligence.Constraint.ConstraintType.SECURITY }
        if (securityConstraints.isNotEmpty()) {
            riskFactors.add(RiskFactor("SECURITY", "Sensitive operation: ${securityConstraints.joinToString(", ") { it.description }}", RiskFactor.Likelihood.POSSIBLE, RiskFactor.Impact.CATASTROPHIC))
            mitigations.add("Require explicit user confirmation; use SafetyGate verification")
        }

        // Dependency risks
        val dependencyConstraints = intent.constraints.filter { it.type == OrionIntentIntelligence.Constraint.ConstraintType.DEPENDENCY }
        if (dependencyConstraints.isNotEmpty() || intent.dependencies.isNotEmpty()) {
            riskFactors.add(RiskFactor("DEPENDENCY", "Task depends on ${intent.dependencies.size} external factors", RiskFactor.Likelihood.POSSIBLE, RiskFactor.Impact.MODERATE))
            mitigations.add("Verify dependencies before execution; implement wait/retry logic")
        }

        // Resource risks
        val batteryMatch = Regex("battery=(\\d+)").find(context.deviceState)
        batteryMatch?.let { match ->
            val battery = match.groupValues[1].toIntOrNull() ?: 100
            if (battery < 20) {
                riskFactors.add(RiskFactor("RESOURCE", "Critical battery level ($battery%)", RiskFactor.Likelihood.ALMOST_CERTAIN, RiskFactor.Impact.MAJOR))
                mitigations.add("Defer non-critical tasks; prioritize battery-saving actions")
            }
        }

        // Complexity risks
        val hardConstraints = intent.constraints.count { it.severity == OrionIntentIntelligence.Constraint.Severity.HARD }
        if (hardConstraints > 3) {
            riskFactors.add(RiskFactor("COMPLEXITY", "$hardConstraints hard constraints increase failure probability", RiskFactor.Likelihood.LIKELY, RiskFactor.Impact.MODERATE))
            mitigations.add("Decompose into smaller tasks; verify each constraint independently")
        }

        // History risks
        if (context.recentFailures > 5) {
            riskFactors.add(RiskFactor("HISTORY", "${context.recentFailures} recent failures indicate systemic issues", RiskFactor.Likelihood.LIKELY, RiskFactor.Impact.MODERATE))
            mitigations.add("Run diagnostics; consider maintenance mode")
        }

        val requiresConfirmation = intent.type == OrionIntentIntelligence.IntentType.TRADING ||
            intent.type == OrionIntentIntelligence.IntentType.SYSTEM_CONTROL ||
            riskFactors.any { it.impact == RiskFactor.Impact.CATASTROPHIC }

        val confirmationReason = when {
            intent.type == OrionIntentIntelligence.IntentType.TRADING -> "Trading operations require explicit user authorization"
            intent.type == OrionIntentIntelligence.IntentType.SYSTEM_CONTROL -> "System changes may affect device operation"
            riskFactors.any { it.impact == RiskFactor.Impact.CATASTROPHIC } -> "Operation carries catastrophic risk if failed"
            else -> null
        }

        return RiskAssessment(
            overallRisk = overallRisk,
            riskFactors = riskFactors,
            mitigationStrategies = mitigations,
            requiresConfirmation = requiresConfirmation,
            confirmationReason = confirmationReason
        )
    }

    private fun buildRationale(
        selected: Strategy?,
        ranked: List<Strategy>,
        intent: OrionIntentIntelligence.Intent,
        riskAssessment: RiskAssessment
    ): String {
        val rationale = StringBuilder()
        rationale.append("Decision for: ${intent.primaryObjective}\n")
        rationale.append("Intent type: ${intent.type}, Urgency: ${intent.urgency}, Confidence: ${String.format("%.0f%%", intent.confidence * 100)}\n\n")

        if (selected != null) {
            rationale.append("SELECTED: ${selected.name} (${selected.approach})\n")
            rationale.append("  Confidence: ${String.format("%.0f%%", selected.confidence * 100)}\n")
            rationale.append("  Est. steps: ${selected.estimatedSteps}, Est. duration: ${selected.estimatedDurationMs / 1000}s\n")
            rationale.append("  Risk: ${selected.riskLevel}\n")
            rationale.append("  Pros: ${selected.pros.joinToString(", ")}\n")
            if (selected.cons.isNotEmpty()) {
                rationale.append("  Cons: ${selected.cons.joinToString(", ")}\n")
            }
            rationale.append("\n")
        }

        rationale.append("ALTERNATIVES CONSIDERED:\n")
        for ((i, alt) in ranked.drop(1).take(3).withIndex()) {
            rationale.append("  ${i + 2}. ${alt.name} (confidence: ${String.format("%.0f%%", alt.confidence * 100)}, risk: ${alt.riskLevel})\n")
        }
        rationale.append("\n")

        rationale.append("RISK ASSESSMENT: ${riskAssessment.overallRisk}\n")
        if (riskAssessment.riskFactors.isNotEmpty()) {
            rationale.append("Risk Factors:\n")
            for (factor in riskAssessment.riskFactors) {
                rationale.append("  - ${factor.category}: ${factor.description} (${factor.likelihood}/${factor.impact})\n")
            }
            rationale.append("\n")
        }
        if (riskAssessment.mitigationStrategies.isNotEmpty()) {
            rationale.append("Mitigations:\n")
            for (mitigation in riskAssessment.mitigationStrategies) {
                rationale.append("  - $mitigation\n")
            }
            rationale.append("\n")
        }

        if (riskAssessment.requiresConfirmation) {
            rationale.append("⚠ REQUIRES USER CONFIRMATION: ${riskAssessment.confirmationReason}\n")
        }

        rationale.append("\nContext: ${context.memoryRecalled} memories, ${context.activeObjectives} active objectives, ${context.availableCapabilities} capabilities, ${context.recentFailures} recent failures")

        return rationale.toString()
    }
}
