package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Orion Decision Engine
 *
 * Selects an appropriate strategy and explains the decision.
 * Integrates with reasoning engine output and provides final decision with full traceability.
 */
class OrionDecisionEngine(
    private val svc: PhoneAiService,
    private val reasoningEngine: OrionReasoningEngine,
    private val intentIntelligence: OrionIntentIntelligence,
    private val capabilityDiscovery: OrionCapabilityDiscovery,
    private val policyEngine: OrionPolicyEngine
) {

    data class Decision(
        val decisionId: String,
        val timestamp: Long,
        val intent: OrionIntentIntelligence.Intent,
        val reasoningResult: OrionReasoningEngine.ReasoningResult,
        val selectedStrategy: OrionReasoningEngine.Strategy,
        val executionPlan: ExecutionPlan,
        val requiresConfirmation: Boolean,
        val confirmationReason: String?,
        val rationale: String,
        val alternativesRejected: List<RejectedAlternative>,
        val metadata: DecisionMetadata
    )

    data class RejectedAlternative(
        val strategyId: String,
        val strategyName: String,
        val reason: String,
        val confidence: Double
    )

    data class ExecutionPlan(
        val planId: String,
        val strategyId: String,
        val steps: List<ExecutionStep>,
        val dependencies: Map<String, List<String>>, // stepId -> depends on stepIds
        val successConditions: List<String>,
        val rollbackPlan: RollbackPlan?,
        val estimatedTotalDurationMs: Long
    )

    data class ExecutionStep(
        val stepId: String,
        val name: String,
        val description: String,
        val tool: String,
        val argsTemplate: JSONObject, // Template with placeholders
        val capability: String,
        val verificationMethod: OrionIntentIntelligence.SuccessCriterion.VerificationMethod,
        val expectedEvidence: String,
        val dependsOn: List<String>, // stepIds this step depends on
        val timeoutMs: Long,
        val retryPolicy: RetryPolicy,
        val isOptional: Boolean = false
    )

    data class RetryPolicy(
        val maxRetries: Int,
        val backoffMs: Long,
        val retryOn: Set<String>, // Error patterns to retry
        val alternativeStrategyOnFailure: String? // Strategy ID to try if all retries fail
    )

    data class RollbackPlan(
        val steps: List<RollbackStep>,
        val triggerConditions: List<String>
    )

    data class RollbackStep(
        val stepId: String,
        val tool: String,
        val args: JSONObject,
        val description: String
    )

    data class DecisionMetadata(
        val reasoningTimeMs: Long,
        val strategiesEvaluated: Int,
        val contextSnapshot: String,
        val policyChecks: List<String>
    )

    /**
     * Make a decision for the given intent.
     */
    fun decide(intent: OrionIntentIntelligence.Intent): Decision {
        val startTime = System.currentTimeMillis()

        // 1. Run reasoning engine
        val reasoningResult = reasoningEngine.reason(intent)

        // 2. Check policy constraints
        val policyChecks = policyEngine.checkConstraints(intent, reasoningResult)

        // 3. Select strategy (highest ranked that passes policy)
        val selectedStrategy = selectStrategy(reasoningResult, policyChecks)

        // 3b. If no strategy passes policy, use safest fallback
        val finalStrategy = selectedStrategy ?: reasoningResult.strategies.firstOrNull()
            ?: throw IllegalStateException("No viable strategy for intent: ${intent.primaryObjective}")

        // 4. Build execution plan
        val executionPlan = buildExecutionPlan(finalStrategy, intent, reasoningResult)

        // 5. Determine if confirmation needed
        val (requiresConfirmation, confirmationReason) = determineConfirmation(intent, finalStrategy, reasoningResult.riskAssessment)

        // 6. Build full rationale
        val rationale = buildDecisionRationale(reasoningResult, finalStrategy, executionPlan, policyChecks)

        // 7. Record rejected alternatives
        val alternativesRejected = reasoningResult.strategies
            .filter { it.id != finalStrategy.id }
            .take(3)
            .map { RejectedAlternative(it.id, it.name, "Lower ranked: ${String.format("%.0f%%", it.confidence * 100)}", it.confidence) }

        val decision = Decision(
            decisionId = "dec_${System.currentTimeMillis()}_${Math.abs(intent.primaryObjective.hashCode())}",
            timestamp = System.currentTimeMillis(),
            intent = intent,
            reasoningResult = reasoningResult,
            selectedStrategy = finalStrategy,
            executionPlan = executionPlan,
            requiresConfirmation = requiresConfirmation,
            confirmationReason = confirmationReason,
            rationale = rationale,
            alternativesRejected = alternativesRejected,
            metadata = DecisionMetadata(
                reasoningTimeMs = System.currentTimeMillis() - startTime,
                strategiesEvaluated = reasoningResult.strategies.size,
                contextSnapshot = reasoningResult.contextUsed.toString(),
                policyChecks = policyChecks
            )
        )

        // Record decision for observatory
        recordDecision(decision)

        return decision
    }

    private fun selectStrategy(
        reasoningResult: OrionReasoningEngine.ReasoningResult,
        policyChecks: List<String>
    ): OrionReasoningEngine.Strategy? {
        // Filter strategies that pass all policy checks
        val viableStrategies = reasoningResult.strategies.filter { strategy ->
            policyChecks.all { check -> !check.contains("BLOCKED: ${strategy.id}") }
        }

        if (viableStrategies.isEmpty()) {
            // Try to find a strategy that only fails soft constraints
            val softViolations = reasoningResult.strategies.mapNotNull { strategy ->
                val violations = policyChecks.filter { check ->
                    check.contains("SOFT_VIOLATION: ${strategy.id}")
                }
                if (violations.isNotEmpty()) strategy to violations else null
            }
            if (softViolations.isNotEmpty()) {
                // Return the one with fewest soft violations
                return softViolations.minByOrNull { it.second.size }?.first
            }
            return null
        }

        // Return highest ranked viable strategy
        return viableStrategies.firstOrNull()
    }

    private fun buildExecutionPlan(
        strategy: OrionReasoningEngine.Strategy,
        intent: OrionIntentIntelligence.Intent,
        reasoningResult: OrionReasoningEngine.ReasoningResult
    ): ExecutionPlan {
        val steps = mutableListOf<ExecutionStep>()
        val dependencies = mutableMapOf<String, List<String>>()

        when (strategy.approach) {
            OrionReasoningEngine.Strategy.Approach.DIRECT_TOOL -> {
                val tool = inferPrimaryTool(intent)
                steps.add(ExecutionStep(
                    stepId = "step_1",
                    name = "Execute $tool",
                    description = "Direct execution of $tool for: ${intent.primaryObjective}",
                    tool = tool,
                    argsTemplate = buildArgsTemplate(tool, intent),
                    capability = tool,
                    verificationMethod = inferVerificationMethod(tool, intent),
                    expectedEvidence = "Action completed and verified",
                    dependsOn = emptyList(),
                    timeoutMs = 30000,
                    retryPolicy = RetryPolicy(1, 1000, setOf("timeout", "temporary"), null)
                ))
            }

            OrionReasoningEngine.Strategy.Approach.SEQUENTIAL_STEPS -> {
                val subSteps = decomposeIntoSteps(intent)
                for ((index, subStep) in subSteps.withIndex()) {
                    val stepId = "step_${index + 1}"
                    val dependsOn = if (index > 0) listOf("step_$index") else emptyList()
                    dependencies[stepId] = dependsOn
                    steps.add(ExecutionStep(
                        stepId = stepId,
                        name = subStep.name,
                        description = subStep.description,
                        tool = subStep.tool,
                        argsTemplate = subStep.argsTemplate,
                        capability = subStep.tool,
                        verificationMethod = subStep.verificationMethod,
                        expectedEvidence = subStep.expectedEvidence,
                        dependsOn = dependsOn,
                        timeoutMs = 60000,
                        retryPolicy = RetryPolicy(2, 2000, setOf("timeout", "temporary", "verification_failed"), "research_then_act")
                    ))
                }
            }

            OrionReasoningEngine.Strategy.Approach.PARALLEL_DELEGATION -> {
                val subtasks = decomposeIntoParallelSubtasks(intent)
                for ((index, subtask) in subtasks.withIndex()) {
                    val stepId = "delegate_${index + 1}"
                    steps.add(ExecutionStep(
                        stepId = stepId,
                        name = "Delegate: ${subtask.topic}",
                        description = "Create specialist agent for: ${subtask.topic}",
                        tool = "ask_agent",
                        argsTemplate = JSONObject().put("task", subtask.topic).put("capabilities", subtask.capabilities),
                        capability = "ask_agent",
                        verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.TOOL_OUTPUT,
                        expectedEvidence = "Agent receipt with results",
                        dependsOn = emptyList(), // All parallel
                        timeoutMs = 120000,
                        retryPolicy = RetryPolicy(1, 5000, setOf("agent_unavailable"), "sequential_steps")
                    ))
                }
                // Integration step
                val integrationStepId = "integrate_results"
                dependencies[integrationStepId] = steps.map { it.stepId }
                steps.add(ExecutionStep(
                    stepId = integrationStepId,
                    name = "Integrate Specialist Results",
                    description = "Combine and synthesize results from all specialist agents",
                    tool = "python_exec",
                    argsTemplate = JSONObject().put("code", "// Integrate results from parallel agents"),
                    capability = "python_exec",
                    verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.TOOL_OUTPUT,
                    expectedEvidence = "Synthesized answer",
                    dependsOn = steps.map { it.stepId },
                    timeoutMs = 30000,
                    retryPolicy = RetryPolicy(1, 1000, setOf(), null)
                ))
            }

            OrionReasoningEngine.Strategy.Approach.RESEARCH_THEN_ACT -> {
                // Research phase
                steps.add(ExecutionStep(
                    stepId = "research",
                    name = "Research Phase",
                    description = "Gather information for: ${intent.primaryObjective}",
                    tool = "web_search",
                    argsTemplate = JSONObject().put("query", intent.primaryObjective).put("max_results", 5),
                    capability = "web_search",
                    verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.TOOL_OUTPUT,
                    expectedEvidence = "Search results with relevant information",
                    dependsOn = emptyList(),
                    timeoutMs = 30000,
                    retryPolicy = RetryPolicy(2, 2000, setOf("timeout", "no_results"), "sequential_steps")
                ))

                // Analysis phase
                steps.add(ExecutionStep(
                    stepId = "analyze",
                    name = "Analyze Findings",
                    description = "Analyze research results and plan execution",
                    tool = "python_exec",
                    argsTemplate = JSONObject().put("code", "// Analyze search results and create execution plan"),
                    capability = "python_exec",
                    verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.TOOL_OUTPUT,
                    expectedEvidence = "Execution plan derived from research",
                    dependsOn = listOf("research"),
                    timeoutMs = 15000,
                    retryPolicy = RetryPolicy(1, 1000, setOf(), null)
                ))

                // Execute phase (simplified - would be expanded based on analysis)
                steps.add(ExecutionStep(
                    stepId = "execute",
                    name = "Execute Plan",
                    description = "Execute the derived plan",
                    tool = inferPrimaryTool(intent),
                    argsTemplate = buildArgsTemplate(inferPrimaryTool(intent), intent),
                    capability = inferPrimaryTool(intent),
                    verificationMethod = inferVerificationMethod(inferPrimaryTool(intent), intent),
                    expectedEvidence = "Action completed and verified",
                    dependsOn = listOf("analyze"),
                    timeoutMs = 60000,
                    retryPolicy = RetryPolicy(1, 2000, setOf("timeout", "verification_failed"), null)
                ))
                dependencies["analyze"] = listOf("research")
                dependencies["execute"] = listOf("analyze")
            }

            OrionReasoningEngine.Strategy.Approach.CODE_EXECUTION -> {
                steps.add(ExecutionStep(
                    stepId = "write_code",
                    name = "Write Code",
                    description = "Generate code for: ${intent.primaryObjective}",
                    tool = "python_exec",
                    argsTemplate = JSONObject().put("code", "// Generate solution code"),
                    capability = "python_exec",
                    verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.TOOL_OUTPUT,
                    expectedEvidence = "Code generated without syntax errors",
                    dependsOn = emptyList(),
                    timeoutMs = 30000,
                    retryPolicy = RetryPolicy(2, 1000, setOf("syntax_error", "timeout"), null)
                ))

                steps.add(ExecutionStep(
                    stepId = "run_code",
                    name = "Execute Code",
                    description = "Run the generated code and capture output",
                    tool = "python_exec",
                    argsTemplate = JSONObject().put("code", "// Execute solution"),
                    capability = "python_exec",
                    verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.TOOL_OUTPUT,
                    expectedEvidence = "Code executed successfully with expected output",
                    dependsOn = listOf("write_code"),
                    timeoutMs = 60000,
                    retryPolicy = RetryPolicy(2, 2000, setOf("runtime_error", "timeout"), "write_code")
                ))
                dependencies["run_code"] = listOf("write_code")

                steps.add(ExecutionStep(
                    stepId = "verify_output",
                    name = "Verify Output",
                    description = "Verify code output matches success criteria",
                    tool = "verify_action",
                    argsTemplate = JSONObject().put("question", "Does the code output satisfy the objective: ${intent.primaryObjective}?"),
                    capability = "verify_action",
                    verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.VERIFY_ACTION,
                    expectedEvidence = "PASS: verification confirms success",
                    dependsOn = listOf("run_code"),
                    timeoutMs = 15000,
                    retryPolicy = RetryPolicy(1, 1000, setOf(), null)
                ))
                dependencies["verify_output"] = listOf("run_code")
            }

            OrionReasoningEngine.Strategy.Approach.BROWSER_AUTOMATION -> {
                val browserSteps = decomposeBrowserAutomation(intent)
                for ((index, step) in browserSteps.withIndex()) {
                    val stepId = "browser_${index + 1}"
                    val dependsOn = if (index > 0) listOf("browser_$index") else emptyList()
                    dependencies[stepId] = dependsOn
                    steps.add(ExecutionStep(
                        stepId = stepId,
                        name = step.name,
                        description = step.description,
                        tool = step.tool,
                        argsTemplate = step.argsTemplate,
                        capability = step.tool,
                        verificationMethod = step.verificationMethod,
                        expectedEvidence = step.expectedEvidence,
                        dependsOn = dependsOn,
                        timeoutMs = 30000,
                        retryPolicy = RetryPolicy(2, 2000, setOf("selector_not_found", "timeout", "navigation_failed"), "research_then_act")
                    ))
                }
            }

            OrionReasoningEngine.Strategy.Approach.SKILL_INVOCATION -> {
                val skillId = strategy.id.removePrefix("skill_")
                steps.add(ExecutionStep(
                    stepId = "invoke_skill",
                    name = "Invoke Skill: $skillId",
                    description = "Execute registered skill for: ${intent.primaryObjective}",
                    tool = "skill_invoke",
                    argsTemplate = JSONObject().put("skill_id", skillId).put("params", buildSkillParams(intent)),
                    capability = "skill_invoke",
                    verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.TOOL_OUTPUT,
                    expectedEvidence = "Skill completed with result",
                    dependsOn = emptyList(),
                    timeoutMs = 60000,
                    retryPolicy = RetryPolicy(1, 2000, setOf("skill_error"), "sequential_steps")
                ))
            }

            OrionReasoningEngine.Strategy.Approach.MACRO_EXECUTION -> {
                val macroId = strategy.id.removePrefix("macro_")
                steps.add(ExecutionStep(
                    stepId = "run_macro",
                    name = "Run Macro: $macroId",
                    description = "Execute saved macro for: ${intent.primaryObjective}",
                    tool = "macro_run",
                    argsTemplate = JSONObject().put("macro_id", macroId),
                    capability = "macro_run",
                    verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.OBJECTIVE_COMPLETE,
                    expectedEvidence = "All macro steps completed",
                    dependsOn = emptyList(),
                    timeoutMs = 120000,
                    retryPolicy = RetryPolicy(1, 5000, setOf("step_failed"), null)
                ))
            }

            OrionReasoningEngine.Strategy.Approach.HYBRID -> {
                // Hybrid combines multiple approaches - build composite plan
                val hybridSteps = buildHybridPlan(intent)
                for ((index, step) in hybridSteps.withIndex()) {
                    val stepId = "hybrid_${index + 1}"
                    val dependsOn = step.dependsOn.map { "hybrid_${it + 1}" }
                    dependencies[stepId] = dependsOn
                    steps.add(ExecutionStep(
                        stepId = stepId,
                        name = step.name,
                        description = step.description,
                        tool = step.tool,
                        argsTemplate = step.argsTemplate,
                        capability = step.tool,
                        verificationMethod = step.verificationMethod,
                        expectedEvidence = step.expectedEvidence,
                        dependsOn = dependsOn,
                        timeoutMs = step.timeoutMs,
                        retryPolicy = step.retryPolicy
                    ))
                }
            }
        }

        // Build rollback plan
        val rollbackPlan = buildRollbackPlan(steps, intent)

        return ExecutionPlan(
            planId = "plan_${System.currentTimeMillis()}",
            strategyId = strategy.id,
            steps = steps,
            dependencies = dependencies,
            successConditions = intent.successCriteria.map { it.description },
            rollbackPlan = rollbackPlan,
            estimatedTotalDurationMs = steps.sumOf { it.timeoutMs }
        )
    }

    private fun inferPrimaryTool(intent: OrionIntentIntelligence.Intent): String {
        return intent.suggestedCapabilities.firstOrNull()
            ?: intent.entities.values.firstOrNull()
            ?: when (intent.type) {
                OrionIntentIntelligence.IntentType.CODING -> "python_exec"
                OrionIntentIntelligence.IntentType.RESEARCH -> "web_search"
                OrionIntentIntelligence.IntentType.AUTOMATION -> "open_app"
                OrionIntentIntelligence.IntentType.TRADING -> "trading_place_order"
                OrionIntentIntelligence.IntentType.SYSTEM_CONTROL -> "computer_settings_wifi_toggle"
                else -> "shell"
            }
    }

    private fun buildArgsTemplate(tool: String, intent: OrionIntentIntelligence.Intent): JSONObject {
        return when (tool) {
            "open_app" -> JSONObject().put("name", extractAppName(intent.primaryObjective))
            "web_search" -> JSONObject().put("query", intent.primaryObjective).put("max_results", 5)
            "python_exec" -> JSONObject().put("code", "// ${intent.primaryObjective}")
            "shell" -> JSONObject().put("command", "echo 'Execute: ${intent.primaryObjective}'")
            "trading_place_order" -> JSONObject().put("symbol", intent.entities["CRYPTO_SYMBOL"] ?: "BTCUSDT").put("side", "BUY").put("qty", 0.001)
            "send_message" -> JSONObject().put("contact", extractContact(intent.primaryObjective)).put("message", extractMessage(intent.primaryObjective))
            "click" -> JSONObject().put("text", extractClickTarget(intent.primaryObjective))
            "type_text" -> JSONObject().put("text", extractTypeText(intent.primaryObjective))
            "reminder_add" -> JSONObject().put("title", intent.primaryObjective).put("time", "in 1 hour")
            "macro_run" -> JSONObject().put("macro_id", extractMacroId(intent.primaryObjective))
            "skill_invoke" -> JSONObject().put("skill_id", extractSkillId(intent.primaryObjective))
            "ask_agent" -> JSONObject().put("task", intent.primaryObjective).put("capabilities", intent.suggestedCapabilities)
            "verify_action" -> JSONObject().put("question", "Was the objective completed: ${intent.primaryObjective}?")
            "verify_send" -> JSONObject().put("contact", extractContact(intent.primaryObjective)).put("message", extractMessage(intent.primaryObjective))
            "browser_navigate" -> JSONObject().put("url", extractUrl(intent.primaryObjective))
            "browser_click" -> JSONObject().put("selector", extractClickSelector(intent.primaryObjective))
            "browser_type" -> JSONObject().put("selector", "input").put("text", extractTypeText(intent.primaryObjective))
            else -> JSONObject().put("input", intent.primaryObjective)
        }
    }

    private fun inferVerificationMethod(tool: String, intent: OrionIntentIntelligence.Intent): OrionIntentIntelligence.SuccessCriterion.VerificationMethod {
        return when (tool) {
            "send_message" -> OrionIntentIntelligence.SuccessCriterion.VerificationMethod.MESSAGE_SENT
            "verify_send" -> OrionIntentIntelligence.SuccessCriterion.VerificationMethod.MESSAGE_SENT
            "verify_action" -> OrionIntentIntelligence.SuccessCriterion.VerificationMethod.VERIFY_ACTION
            "shell" -> OrionIntentIntelligence.SuccessCriterion.VerificationMethod.SHELL_EXIT_CODE
            "python_exec" -> OrionIntentIntelligence.SuccessCriterion.VerificationMethod.TOOL_OUTPUT
            "trading_place_order" -> OrionIntentIntelligence.SuccessCriterion.VerificationMethod.EXTERNAL_API
            "macro_run", "objective_graph" -> OrionIntentIntelligence.SuccessCriterion.VerificationMethod.OBJECTIVE_COMPLETE
            "memory" -> OrionIntentIntelligence.SuccessCriterion.VerificationMethod.MEMORY_RECORDED
            "open_app", "click", "type_text", "browser_navigate", "browser_click", "browser_type" -> OrionIntentIntelligence.SuccessCriterion.VerificationMethod.SCREEN_CHANGE
            else -> OrionIntentIntelligence.SuccessCriterion.VerificationMethod.TOOL_OUTPUT
        }
    }

    private fun decomposeIntoSteps(intent: OrionIntentIntelligence.Intent): List<SubStep> {
        val steps = mutableListOf<SubStep>()

        // Use objective graph if available
        val graph = OrionObjectiveGraph.load(svc)
        if (graph != null && graph.nodes.isNotEmpty()) {
            val readyNodes = graph.nodes.filter { it.status == OrionObjectiveGraph.NodeStatus.READY }
            for (node in readyNodes.take(10)) {
                steps.add(SubStep(
                    name = "Objective: ${node.objective}",
                    description = "Execute step for objective ${node.id}",
                    tool = inferPrimaryToolFromObjective(node.objective),
                    argsTemplate = buildArgsTemplate(inferPrimaryToolFromObjective(node.objective), intent),
                    verificationMethod = inferVerificationMethod(inferPrimaryToolFromObjective(node.objective), intent),
                    expectedEvidence = "Objective ${node.id} completed"
                ))
            }
            if (steps.isNotEmpty()) return steps
        }

        // Fallback: heuristic decomposition
        val input = intent.primaryObjective.lowercase()

        // Common patterns
        if (input.contains("open") && input.contains("send")) {
            steps.add(SubStep("Open App", "Open the target application", "open_app", buildArgsTemplate("open_app", intent), OrionIntentIntelligence.SuccessCriterion.VerificationMethod.SCREEN_CHANGE, "App opened"))
            steps.add(SubStep("Send Message", "Compose and send message", "send_message", buildArgsTemplate("send_message", intent), OrionIntentIntelligence.SuccessCriterion.VerificationMethod.MESSAGE_SENT, "Message sent"))
        } else if (input.contains("research") || input.contains("search")) {
            steps.add(SubStep("Search", "Search for information", "web_search", buildArgsTemplate("web_search", intent), OrionIntentIntelligence.SuccessCriterion.VerificationMethod.TOOL_OUTPUT, "Search results"))
            steps.add(SubStep("Analyze", "Analyze and summarize findings", "python_exec", buildArgsTemplate("python_exec", intent), OrionIntentIntelligence.SuccessCriterion.VerificationMethod.TOOL_OUTPUT, "Summary generated"))
        } else if (input.contains("code") || input.contains("script")) {
            steps.add(SubStep("Write Code", "Generate code solution", "python_exec", buildArgsTemplate("python_exec", intent), OrionIntentIntelligence.SuccessCriterion.VerificationMethod.TOOL_OUTPUT, "Code written"))
            steps.add(SubStep("Run Code", "Execute and test code", "python_exec", buildArgsTemplate("python_exec", intent), OrionIntentIntelligence.SuccessCriterion.VerificationMethod.TOOL_OUTPUT, "Code executed"))
            steps.add(SubStep("Verify", "Verify output matches requirements", "verify_action", buildArgsTemplate("verify_action", intent), OrionIntentIntelligence.SuccessCriterion.VerificationMethod.VERIFY_ACTION, "Verified"))
        } else {
            // Generic single step
            steps.add(SubStep(
                "Execute Action",
                "Perform the requested action",
                inferPrimaryTool(intent),
                buildArgsTemplate(inferPrimaryTool(intent), intent),
                inferVerificationMethod(inferPrimaryTool(intent), intent),
                "Action completed"
            ))
        }

        return steps
    }

    private data class SubStep(
        val name: String,
        val description: String,
        val tool: String,
        val argsTemplate: JSONObject,
        val verificationMethod: OrionIntentIntelligence.SuccessCriterion.VerificationMethod,
        val expectedEvidence: String
    )

    private fun inferPrimaryToolFromObjective(objective: String): String {
        val lower = objective.lowercase()
        return when {
            lower.contains("open") -> "open_app"
            lower.contains("search") || lower.contains("research") -> "web_search"
            lower.contains("code") || lower.contains("script") -> "python_exec"
            lower.contains("send") || lower.contains("message") -> "send_message"
            lower.contains("click") || lower.contains("tap") -> "click"
            lower.contains("type") -> "type_text"
            lower.contains("trade") || lower.contains("buy") || lower.contains("sell") -> "trading_place_order"
            lower.contains("remind") -> "reminder_add"
            lower.contains("browser") || lower.contains("navigate") -> "browser_navigate"
            else -> "shell"
        }
    }

    private fun decomposeIntoParallelSubtasks(intent: OrionIntentIntelligence.Intent): List<ParallelSubtask> {
        val subtasks = mutableListOf<ParallelSubtask>()
        val input = intent.primaryObjective

        // Split by common separators
        val parts = input.split(Regex("(?i)\\band\\b|,|;|then|also")).map { it.trim() }.filter { it.length > 10 }

        if (parts.size >= 2) {
            for (part in parts.take(4)) {
                subtasks.add(ParallelSubtask(
                    topic = part.take(100),
                    capabilities = inferCapabilitiesForSubtask(part)
                ))
            }
        } else {
            // Default: split by intent type
            when (intent.type) {
                OrionIntentIntelligence.IntentType.RESEARCH -> {
                    subtasks.add(ParallelSubtask("Search web for: $input", listOf("web_search", "fetch_url")))
                    subtasks.add(ParallelSubtask("Search documentation for: $input", listOf("browser_navigate", "browser_snapshot")))
                }
                OrionIntentIntelligence.IntentType.CODING -> {
                    subtasks.add(ParallelSubtask("Research best practices for: $input", listOf("web_search")))
                    subtasks.add(ParallelSubtask("Check existing code patterns", listOf("file_read", "shell")))
                }
                else -> {
                    subtasks.add(ParallelSubtask("Primary task: $input", intent.suggestedCapabilities))
                    if (intent.suggestedCapabilities.size > 1) {
                        subtasks.add(ParallelSubtask("Verify and validate", listOf("verify_action", "read_screen")))
                    }
                }
            }
        }

        return subtasks
    }

    private data class ParallelSubtask(
        val topic: String,
        val capabilities: List<String>
    )

    private fun inferCapabilitiesForSubtask(subtask: String): List<String> {
        val lower = subtask.lowercase()
        return when {
            lower.contains("search") || lower.contains("research") -> listOf("web_search", "fetch_url")
            lower.contains("code") -> listOf("python_exec", "shell")
            lower.contains("browse") || lower.contains("navigate") -> listOf("browser_navigate", "browser_snapshot")
            lower.contains("trade") -> listOf("trading_ticker", "trading_market_info")
            else -> listOf("shell", "verify_action")
        }
    }

    private fun decomposeBrowserAutomation(intent: OrionIntentIntelligence.Intent): List<BrowserStep> {
        val steps = mutableListOf<BrowserStep>()
        val input = intent.primaryObjective
        val url = extractUrl(input)

        steps.add(BrowserStep("Navigate", "Navigate to target URL", "browser_navigate",
            JSONObject().put("url", url), OrionIntentIntelligence.SuccessCriterion.VerificationMethod.SCREEN_CHANGE, "Page loaded"))

        // Add interaction steps based on intent
        if (input.contains("click") || input.contains("tap")) {
            steps.add(BrowserStep("Click Element", "Click target element", "browser_click",
                JSONObject().put("selector", extractClickSelector(input)), OrionIntentIntelligence.SuccessCriterion.VerificationMethod.SCREEN_CHANGE, "Element clicked"))
        }
        if (input.contains("type") || input.contains("enter") || input.contains("input")) {
            steps.add(BrowserStep("Type Text", "Enter text in field", "browser_type",
                JSONObject().put("selector", "input").put("text", extractTypeText(input)), OrionIntentIntelligence.SuccessCriterion.VerificationMethod.SCREEN_CHANGE, "Text entered"))
        }

        // Always verify at end
        steps.add(BrowserStep("Verify Result", "Verify automation outcome", "verify_action",
            JSONObject().put("question", "Was the browser automation successful for: $input?"),
            OrionIntentIntelligence.SuccessCriterion.VerificationMethod.VERIFY_ACTION, "Verified"))

        return steps
    }

    private data class BrowserStep(
        val name: String,
        val description: String,
        val tool: String,
        val argsTemplate: JSONObject,
        val verificationMethod: OrionIntentIntelligence.SuccessCriterion.VerificationMethod,
        val expectedEvidence: String
    )

    private fun buildHybridPlan(intent: OrionIntentIntelligence.Intent): List<HybridStep> {
        val steps = mutableListOf<HybridStep>()
        var stepNum = 0

        // Phase 1: Research
        stepNum++
        steps.add(HybridStep(
            name = "Phase 1: Research",
            description = "Gather comprehensive information",
            tool = "web_search",
            argsTemplate = JSONObject().put("query", intent.primaryObjective).put("max_results", 10),
            verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.TOOL_OUTPUT,
            expectedEvidence = "Research results collected",
            dependsOn = emptyList(),
            timeoutMs = 45000,
            retryPolicy = RetryPolicy(2, 2000, setOf("timeout", "no_results"), null)
        ))

        // Phase 2: Plan
        stepNum++
        steps.add(HybridStep(
            name = "Phase 2: Plan",
            description = "Create detailed execution plan from research",
            tool = "python_exec",
            argsTemplate = JSONObject().put("code", "// Create execution plan from research"),
            verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.TOOL_OUTPUT,
            expectedEvidence = "Execution plan generated",
            dependsOn = listOf(stepNum - 1),
            timeoutMs = 30000,
            retryPolicy = RetryPolicy(1, 1000, setOf(), null)
        ))

        // Phase 3: Delegate parallel subtasks
        stepNum++
        val subtasks = decomposeIntoParallelSubtasks(intent)
        if (subtasks.size > 1) {
            steps.add(HybridStep(
                name = "Phase 3: Parallel Delegation",
                description = "Delegate subtasks to specialist agents",
                tool = "ask_agent",
                argsTemplate = JSONObject().put("task", subtasks[0].topic).put("capabilities", subtasks[0].capabilities),
                verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.TOOL_OUTPUT,
                expectedEvidence = "Agent receipts received",
                dependsOn = listOf(stepNum - 1),
                timeoutMs = 120000,
                retryPolicy = RetryPolicy(1, 5000, setOf("agent_unavailable"), null)
            ))
        }

        // Phase 4: Execute main action
        stepNum++
        steps.add(HybridStep(
            name = "Phase 4: Execute",
            description = "Execute primary action",
            tool = inferPrimaryTool(intent),
            argsTemplate = buildArgsTemplate(inferPrimaryTool(intent), intent),
            verificationMethod = inferVerificationMethod(inferPrimaryTool(intent), intent),
            expectedEvidence = "Primary action completed",
            dependsOn = listOf(stepNum - 1),
            timeoutMs = 60000,
            retryPolicy = RetryPolicy(2, 2000, setOf("timeout", "verification_failed"), null)
        ))

        // Phase 5: Integrate and verify
        stepNum++
        steps.add(HybridStep(
            name = "Phase 5: Integrate & Verify",
            description = "Combine results and verify overall objective",
            tool = "verify_action",
            argsTemplate = JSONObject().put("question", "Was the overall objective achieved: ${intent.primaryObjective}?"),
            verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.VERIFY_ACTION,
            expectedEvidence = "PASS: Objective verified",
            dependsOn = listOf(stepNum - 1),
            timeoutMs = 15000,
            retryPolicy = RetryPolicy(1, 1000, setOf(), null)
        ))

        return steps
    }

    private data class HybridStep(
        val name: String,
        val description: String,
        val tool: String,
        val argsTemplate: JSONObject,
        val verificationMethod: OrionIntentIntelligence.SuccessCriterion.VerificationMethod,
        val expectedEvidence: String,
        val dependsOn: List<Int>,
        val timeoutMs: Long,
        val retryPolicy: RetryPolicy
    )

    private fun buildRollbackPlan(steps: List<ExecutionStep>, intent: OrionIntentIntelligence.Intent): RollbackPlan? {
        val rollbackSteps = mutableListOf<RollbackStep>()

        // Add rollback for irreversible actions
        for (step in steps.reversed()) {
            when (step.tool) {
                "trading_place_order" -> rollbackSteps.add(RollbackStep(
                    stepId = "rollback_${step.stepId}",
                    tool = "trading_cancel_order",
                    args = JSONObject().put("order_id", "{LAST_ORDER_ID}"),
                    description = "Cancel placed order"
                ))
                "file_write" -> rollbackSteps.add(RollbackStep(
                    stepId = "rollback_${step.stepId}",
                    tool = "file_delete",
                    args = step.argsTemplate.optJSONObject("path")?.let { JSONObject().put("path", it.optString("path")) } ?: JSONObject(),
                    description = "Delete created file"
                ))
                "send_message" -> rollbackSteps.add(RollbackStep(
                    stepId = "rollback_${step.stepId}",
                    tool = "shell",
                    args = JSONObject().put("command", "echo 'Message sent - cannot recall, notify recipient'"),
                    description = "Notify about sent message (cannot truly recall)"
                ))
                "computer_settings_restart", "computer_settings_shutdown" -> rollbackSteps.add(RollbackStep(
                    stepId = "rollback_${step.stepId}",
                    tool = "shell",
                    args = JSONObject().put("command", "echo 'System action initiated - cannot rollback'"),
                    description = "System action cannot be rolled back"
                ))
            }
        }

        return if (rollbackSteps.isNotEmpty()) {
            RollbackPlan(
                steps = rollbackSteps,
                triggerConditions = listOf("verification_failed", "user_cancelled", "critical_error", "timeout_exceeded")
            )
        } else null
    }

    private fun determineConfirmation(
        intent: OrionIntentIntelligence.Intent,
        strategy: OrionReasoningEngine.Strategy,
        riskAssessment: OrionReasoningEngine.RiskAssessment
    ): Pair<Boolean, String?> {
        // Trading always requires confirmation
        if (intent.type == OrionIntentIntelligence.IntentType.TRADING) {
            return true to "Trading orders require explicit user authorization"
        }

        // System control requires confirmation
        if (intent.type == OrionIntentIntelligence.IntentType.SYSTEM_CONTROL) {
            return true to "System changes (restart, shutdown, Wi-Fi) require confirmation"
        }

        // High/critical risk requires confirmation
        if (riskAssessment.overallRisk in setOf(OrionReasoningEngine.Strategy.RiskLevel.HIGH, OrionReasoningEngine.Strategy.RiskLevel.CRITICAL)) {
            return true to "High-risk operation requires confirmation: ${riskAssessment.confirmationReason}"
        }

        // Catastrophic impact risk factors
        if (riskAssessment.riskFactors.any { it.impact == OrionReasoningEngine.RiskFactor.Impact.CATASTROPHIC }) {
            return true to "Operation has catastrophic risk factors: ${riskAssessment.riskFactors.filter { it.impact == OrionReasoningEngine.RiskFactor.Impact.CATASTROPHIC }.joinToString(", ") { it.description }}"
        }

        // Hard security constraints
        if (intent.constraints.any { it.type == OrionIntentIntelligence.Constraint.ConstraintType.SECURITY && it.severity == OrionIntentIntelligence.Constraint.Severity.HARD }) {
            return true to "Security-sensitive operation requires confirmation"
        }

        return false to null
    }

    private fun buildDecisionRationale(
        reasoningResult: OrionReasoningEngine.ReasoningResult,
        selected: OrionReasoningEngine.Strategy,
        executionPlan: ExecutionPlan,
        policyChecks: List<String>
    ): String {
        val rationale = StringBuilder()
        rationale.append("=== ORION DECISION RECORD ===\n")
        rationale.append("Decision ID: ${selected.id}_${System.currentTimeMillis()}\n")
        rationale.append("Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}\n\n")

        rationale.append("INTENT ANALYSIS:\n")
        rationale.append("  Objective: ${reasoningResult.intent.primaryObjective}\n")
        rationale.append("  Type: ${reasoningResult.intent.type}\n")
        rationale.append("  Urgency: ${reasoningResult.intent.urgency}\n")
        rationale.append("  Confidence: ${String.format("%.0f%%", reasoningResult.intent.confidence * 100)}\n")
        rationale.append("  Entities: ${reasoningResult.intent.entities.keys.joinToString(", ")}\n")
        rationale.append("  Constraints: ${reasoningResult.intent.constraints.joinToString(", ") { "${it.type}/${it.severity}: ${it.description}" }}\n\n")

        rationale.append("REASONING:\n")
        rationale.append(reasoningResult.decisionRationale).append("\n\n")

        rationale.append("SELECTED STRATEGY: ${selected.name}\n")
        rationale.append("  Approach: ${selected.approach}\n")
        rationale.append("  Confidence: ${String.format("%.0f%%", selected.confidence * 100)}\n")
        rationale.append("  Risk: ${selected.riskLevel}\n")
        rationale.append("  Est. Duration: ${selected.estimatedDurationMs / 1000}s\n")
        rationale.append("  Est. Steps: ${selected.estimatedSteps}\n\n")

        rationale.append("EXECUTION PLAN:\n")
        rationale.append("  Plan ID: ${executionPlan.planId}\n")
        rationale.append("  Steps: ${executionPlan.steps.size}\n")
        rationale.append("  Total Est. Duration: ${executionPlan.estimatedTotalDurationMs / 1000}s\n")
        for (step in executionPlan.steps) {
            rationale.append("  - ${step.stepId}: ${step.name} (${step.tool})")
            if (step.dependsOn.isNotEmpty()) rationale.append(" [depends on: ${step.dependsOn.joinToString(", ")}]")
            rationale.append("\n")
        }
        rationale.append("\n")

        rationale.append("SUCCESS CONDITIONS:\n")
        for (condition in executionPlan.successConditions) {
            rationale.append("  - $condition\n")
        }
        rationale.append("\n")

        if (executionPlan.rollbackPlan != null) {
            rationale.append("ROLLBACK PLAN:\n")
            rationale.append("  Triggers: ${executionPlan.rollbackPlan!!.triggerConditions.joinToString(", ")}\n")
            for (step in executionPlan.rollbackPlan!!.steps) {
                rationale.append("  - ${step.stepId}: ${step.description} (${step.tool})\n")
            }
            rationale.append("\n")
        }

        rationale.append("POLICY CHECKS:\n")
        for (check in policyChecks) {
            rationale.append("  - $check\n")
        }
        rationale.append("\n")

        if (requiresConfirmation) {
            rationale.append("⚠ CONFIRMATION REQUIRED: $confirmationReason\n")
        }

        rationale.append("\n=== END DECISION RECORD ===")

        return rationale.toString()
    }

    private fun recordDecision(decision: Decision) {
        // Planning decisions are ephemeral; observability is via OrionEvidenceLedger
        // and the Intelligence Observatory (Phase 0L)
    }

    // Helper methods for argument extraction
    private fun extractAppName(input: String): String {
        val patterns = listOf(
            Regex("(?i)open\\s+(\\w+(?:\\s+\\w+)*)"),
            Regex("(?i)launch\\s+(\\w+(?:\\s+\\w+)*)"),
            Regex("(?i)start\\s+(\\w+(?:\\s+\\w+)*)")
        )
        for (pattern in patterns) {
            val match = pattern.find(input)
            if (match != null) return match.groupValues[1]
        }
        return "WhatsApp" // Default
    }

    private fun extractContact(input: String): String {
        val patterns = listOf(
            Regex("(?i)(?:to|message)\\s+(\\w+(?:\\s+\\w+)*)"),
            Regex("(?i)(?:contact|send\\s+to)\\s+(\\w+(?:\\s+\\w+)*)")
        )
        for (pattern in patterns) {
            val match = pattern.find(input)
            if (match != null) return match.groupValues[1]
        }
        return "Mom" // Default
    }

    private fun extractMessage(input: String): String {
        val patterns = listOf(
            Regex("(?i)(?:say|message|text)\\s+[\"']?([^\"']+)[\"']?"),
            Regex("(?i)(?:saying|with)\\s+[\"']?([^\"']+)[\"']?")
        )
        for (pattern in patterns) {
            val match = pattern.find(input)
            if (match != null) return match.groupValues[1]
        }
        return "Hello from Orion"
    }

    private fun extractClickTarget(input: String): String {
        val patterns = listOf(
            Regex("(?i)click\\s+(?:on\\s+)?[\"']?([^\"']+)[\"']?"),
            Regex("(?i)tap\\s+(?:on\\s+)?[\"']?([^\"']+)[\"']?")
        )
        for (pattern in patterns) {
            val match = pattern.find(input)
            if (match != null) return match.groupValues[1]
        }
        return "Send"
    }

    private fun extractTypeText(input: String): String {
        val patterns = listOf(
            Regex("(?i)type\\s+[\"']?([^\"']+)[\"']?"),
            Regex("(?i)enter\\s+[\"']?([^\"']+)[\"']?"),
            Regex("(?i)input\\s+[\"']?([^\"']+)[\"']?")
        )
        for (pattern in patterns) {
            val match = pattern.find(input)
            if (match != null) return match.groupValues[1]
        }
        return "Test input"
    }

    private fun extractUrl(input: String): String {
        val match = Regex("https?://[^\\s]+").find(input)
        return match?.value ?: "https://google.com"
    }

    private fun extractClickSelector(input: String): String {
        val patterns = listOf(
            Regex("(?i)click\\s+[\"']?([^\"']+)[\"']?"),
            Regex("(?i)button\\s+[\"']?([^\"']+)[\"']?")
        )
        for (pattern in patterns) {
            val match = pattern.find(input)
            if (match != null) return match.groupValues[1]
        }
        return "button:contains('Submit')"
    }

    private fun extractMacroId(input: String): String {
        val macros = OrionMacroExecutor.listMacros(svc)
        val match = macros.find { it.name.lowercase().contains(input.lowercase().take(20)) }
        return match?.id ?: "default_macro"
    }

    private fun extractSkillId(input: String): String {
        val skills = OrionSkillsCapabilityExecutor.discover(svc, input, 1)
        return skills.firstOrNull()?.id ?: "default_skill"
    }

    private fun buildSkillParams(intent: OrionIntentIntelligence.Intent): JSONObject {
        val params = JSONObject()
        for ((key, value) in intent.entities) {
            params.put(key, value)
        }
        params.put("objective", intent.primaryObjective)
        return params
    }
}
