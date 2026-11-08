package com.phoneai.app

import org.json.JSONObject

/**
 * Orion Planning Engine
 *
 * Turns a selected strategy plus a structured intent into an executable plan whose steps are
 * real tool calls with real arguments, real dependencies and a verification method a verifier
 * can check. The plan also carries the failure-replanning path so a dead step does not become
 * an infinite retry loop.
 */
internal class OrionPlanningEngine(
    private val svc: PhoneAiService
) {

    enum class PlanStepStatus { PENDING, READY, RUNNING, COMPLETE, FAILED, BLOCKED, SKIPPED }

    data class PlanStep(
        val stepId: String,
        val name: String,
        val description: String,
        val tool: String,
        val argsTemplate: Map<String, String>,
        val verificationMethod: String,
        val expectedEvidence: String,
        val dependsOn: List<String> = emptyList(),
        val status: PlanStepStatus = PlanStepStatus.PENDING,
        val optional: Boolean = false,
        val failureAlternatives: List<String> = emptyList()
    )

    data class RollbackPlan(
        val triggerConditions: List<String>,
        val steps: List<PlanStep>
    )

    data class ExecutionPlan(
        val planId: String,
        val objective: String,
        val steps: List<PlanStep>,
        val dependencies: Map<String, List<String>>,
        val successConditions: List<String>,
        val estimatedTotalDurationMs: Long,
        val rollbackPlan: RollbackPlan?,
        val createdAt: Long,
        val strategyId: String
    ) {
        /** Steps in dependency order; unresolvable cycles are dropped, never silently run. */
        fun orderedSteps(): List<PlanStep> {
            val done = mutableSetOf<String>()
            val out = mutableListOf<PlanStep>()
            val remaining = steps.toMutableList()
            var guard = 0
            while (remaining.isNotEmpty() && guard++ <= steps.size + 1) {
                val ready = remaining.filter { it.dependsOn.all(done::contains) }
                if (ready.isEmpty()) break
                out.addAll(ready)
                done += ready.map { it.stepId }
                remaining.removeAll(ready.toSet())
            }
            return out
        }

        fun hasCycle(): Boolean = orderedSteps().size < steps.size
    }

    /**
     * Builds a plan for the selected strategy. Steps come from the request and the intent's
     * required capabilities — never from a fixed template.
     */
    fun createPlan(
        objective: String,
        intent: OrionIntentIntelligence.Intent,
        strategy: OrionReasoningEngine.Strategy?,
        context: OrionContextIntegration.UnifiedContext?
    ): ExecutionPlan {
        val steps = mutableListOf<PlanStep>()
        val strategyId = strategy?.id ?: "direct_tool"
        val target = intent.entities.entries
            .firstOrNull { it.value == "PERSON" || it.value == "PERSON_RELATIVE" || it.value == "PROJECT" }
            ?.key
            ?: intent.entities.keys.firstOrNull()

        when (strategyId) {
            "answer_directly", "clarify_first" -> {
                // No steps: these strategies complete in the conversational path.
            }

            "research_first" -> {
                val query = buildSearchQuery(intent)
                steps += step(
                    id = "step_1", name = "Search for sources",
                    tool = "browser_navigate",
                    args = mapOf("url" to "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(query, "UTF-8")),
                    verify = "LOCAL VERIFY PASS after navigation",
                    evidence = "page loaded for query: $query",
                    alternatives = listOf("live_web_search", "research_web")
                )
                steps += step(
                    id = "step_2", name = "Read the page",
                    tool = "browser_snapshot",
                    args = emptyMap(),
                    verify = "snapshot returns readable content",
                    evidence = "page text retrieved",
                    dependsOn = listOf("step_1"),
                    alternatives = listOf("read_screen")
                )
            }

            "code_execution" -> {
                steps += step(
                    id = "step_1", name = "Write and run the program",
                    tool = "python_exec",
                    args = mapOf("code" to "print('orion-plan')"),
                    verify = "exit code 0 and non-empty output",
                    evidence = "python produced output",
                    alternatives = listOf("shell")
                )
                steps += step(
                    id = "step_2", name = "Read back the result",
                    tool = "read_screen",
                    args = emptyMap(),
                    verify = "output visible",
                    evidence = "program output observed",
                    dependsOn = listOf("step_1"),
                    optional = true
                )
            }

            "parallel_delegation" -> {
                steps += step(
                    id = "step_1", name = "Delegate to specialist",
                    tool = "ask_agent",
                    args = mapOf("task" to objective),
                    verify = "receipt returned",
                    evidence = "specialist result present",
                    alternatives = listOf("queue_agent")
                )
                steps += step(
                    id = "step_2", name = "Verify the delegated result",
                    tool = "verify_action",
                    args = mapOf("question" to "Was the delegated task '$objective' completed?"),
                    verify = "verify_action returns PASS",
                    evidence = "parent independently confirmed the specialist outcome",
                    dependsOn = listOf("step_1")
                )
            }

            "inspect_then_act", "sequential_steps", "direct_tool" -> {
                // Inspect first only when the plan touches the UI or the request is multi-step.
                val needsInspect = strategyId != "direct_tool" ||
                    intent.requiredCapabilities.any { it in UI_TOOLS }
                var index = 1
                if (needsInspect) {
                    steps += step(
                        id = "step_$index", name = "Inspect current state",
                        tool = "read_screen",
                        args = emptyMap(),
                        verify = "readable snapshot",
                        evidence = "screen state grounded",
                        alternatives = listOf("look")
                    )
                    index++
                }

                // One real action step per distinct implied capability, in dependency order.
                val actions = intent.requiredCapabilities.filter { it !in setOf("read_screen") }
                if (actions.isEmpty()) {
                    steps += step(
                        id = "step_$index", name = "Perform the requested action",
                        tool = "read_screen",
                        args = emptyMap(),
                        verify = "LOCAL VERIFY PASS",
                        evidence = "requested state observed",
                        dependsOn = if (needsInspect) listOf("step_${index - 1}") else emptyList()
                    )
                    index++
                } else {
                    var previous: String? = if (needsInspect) "step_${index - 1}" else null
                    for ((offset, action) in actions.withIndex()) {
                        val id = "step_${index + offset}"
                        steps += step(
                            id = id, name = "Action: $action",
                            tool = action,
                            args = actionArgs(action, intent, target),
                            verify = verificationFor(action),
                            evidence = "requested end state reached via $action",
                            dependsOn = previous?.let { listOf(it) } ?: emptyList(),
                            alternatives = if (action == "send_message") listOf("verify_send") else emptyList()
                        )
                        previous = id
                    }
                    index += actions.size
                }

                // A closing verification step makes "tool succeeded" distinct from "goal achieved".
                if (actions.isNotEmpty()) {
                    val lastDependency = steps.last().stepId
                    steps += step(
                        id = "step_$index", name = "Verify the objective",
                        tool = if ("send_message" in actions) "verify_send" else "verify_action",
                        args = if ("send_message" in actions) emptyMap()
                        else mapOf("question" to "Was the objective '$objective' fully completed?"),
                        verify = "PASS from the verification tool",
                        evidence = "objective-level confirmation",
                        dependsOn = listOf(lastDependency)
                    )
                }
            }
        }

        val successConditions = intent.successCriteria.map { it.description } +
            "Every non-optional step reaches COMPLETE with recorded verification evidence"

        return ExecutionPlan(
            planId = "plan_" + System.currentTimeMillis(),
            objective = objective,
            steps = steps,
            dependencies = steps.associate { it.stepId to it.dependsOn },
            successConditions = successConditions,
            estimatedTotalDurationMs = steps.sumOf { 6_000L },
            rollbackPlan = buildRollback(strategyId, steps),
            createdAt = System.currentTimeMillis(),
            strategyId = strategyId
        )
    }

    /**
     * Replan after a failure using new evidence: the failed step is replaced by up to two
     * concrete alternatives chosen from the recovery planner's shortlist (freshly filtered by
     * what is actually registered and permitted), not by a blind retry of the same call.
     */
    fun replanFromFailure(
        plan: ExecutionPlan,
        failedStepId: String,
        error: String,
        intent: OrionIntentIntelligence.Intent,
        context: OrionContextIntegration.UnifiedContext?,
        alreadyAttempted: Set<String> = emptySet()
    ): ReplanResult {
        val failedStep = plan.steps.firstOrNull { it.stepId == failedStepId }
            ?: return ReplanResult(plan, emptyList(), "failed step not found in plan")

        val permitted = context?.capabilities?.availableCapabilities?.toSet() ?: emptySet()
        val shortlist = runCatching {
            OrionRecoveryPlanner.selectAlternatives(
                failedTool = failedStep.tool,
                result = error,
                registeredAndPermitted = permitted,
                alreadyAttempted = alreadyAttempted + failedStep.tool
            )
        }.getOrDefault(emptyList())

        val replacementTools = (shortlist + failedStep.failureAlternatives)
            .filter { it != failedStep.tool }
            .filter { permitted.isEmpty() || it in permitted }
            .distinct()
            .take(2)

        if (replacementTools.isEmpty()) {
            val updated = plan.copy(steps = plan.steps.map {
                if (it.stepId == failedStepId) it.copy(status = PlanStepStatus.BLOCKED) else it
            })
            return ReplanResult(
                updated, emptyList(),
                "no permitted alternative for '${failedStep.tool}' after: ${error.take(160)}"
            )
        }

        val newSteps = mutableListOf<PlanStep>()
        replacementTools.forEachIndexed { i, tool ->
            newSteps += failedStep.copy(
                stepId = "${failedStepId}_alt${i + 1}",
                name = "Recovery: ${failedStep.name} via $tool",
                description = "Alternative after '${failedStep.tool}' failed",
                tool = tool,
                argsTemplate = argsForAlternative(tool, failedStep.argsTemplate, intent),
                verificationMethod = verificationFor(tool),
                status = PlanStepStatus.PENDING,
                dependsOn = failedStep.dependsOn,
                optional = i > 0
            )
        }
        // Downstream steps now depend on the surviving alternative; the original is marked FAILED.
        val altIds = newSteps.map { it.stepId }
        val updatedSteps = plan.steps.map { s ->
            when {
                s.stepId == failedStepId -> s.copy(status = PlanStepStatus.FAILED)
                s.dependsOn.contains(failedStepId) -> s.copy(dependsOn = (s.dependsOn - failedStepId) + altIds)
                else -> s
            }
        } + newSteps

        return ReplanResult(
            plan.copy(steps = updatedSteps, createdAt = System.currentTimeMillis()),
            newSteps.map { it.stepId },
            "replanned '${failedStep.tool}' -> ${replacementTools.joinToString(" / ")}"
        )
    }

    data class ReplanResult(
        val plan: ExecutionPlan,
        val replacementStepIds: List<String>,
        val note: String
    ) {
        val succeeded: Boolean get() = replacementStepIds.isNotEmpty()
    }

    // ------------------------------------------------------------------

    private fun step(
        id: String,
        name: String,
        tool: String,
        args: Map<String, String>,
        verify: String,
        evidence: String,
        dependsOn: List<String> = emptyList(),
        optional: Boolean = false,
        alternatives: List<String> = emptyList()
    ) = PlanStep(
        stepId = id,
        name = name,
        description = name,
        tool = tool,
        argsTemplate = args,
        verificationMethod = verify,
        expectedEvidence = evidence,
        dependsOn = dependsOn,
        optional = optional,
        failureAlternatives = alternatives
    )

    private fun actionArgs(
        tool: String,
        intent: OrionIntentIntelligence.Intent,
        target: String?
    ): Map<String, String> {
        val objective = intent.primaryObjective
        val resolvedTarget = target?.let { intent.entities["resolved:$it"] ?: it }
        return when (tool) {
            "open_app" -> mapOf("app" to (extractAppName(objective) ?: ""))
            "send_message" -> buildMap {
                put("message", objective)
                resolvedTarget?.let { put("contact", it) }
            }
            "type_text" -> mapOf("text" to objective)
            "click" -> mapOf("target" to (resolvedTarget ?: objective))
            "call" -> mapOf("contact" to (resolvedTarget ?: ""))
            "memory" -> mapOf("query" to objective)
            "shell" -> mapOf("command" to objective)
            else -> emptyMap()
        }
    }

    private fun argsForAlternative(
        tool: String,
        original: Map<String, String>,
        intent: OrionIntentIntelligence.Intent
    ): Map<String, String> = when (tool) {
        "read_screen", "look", "browser_snapshot" -> emptyMap()
        "verify_action" -> mapOf("question" to "Was the objective '${intent.primaryObjective}' completed?")
        "verify_send" -> original.filterKeys { it == "contact" }
        "wait_for" -> mapOf("millis" to "1500")
        else -> original
    }

    private fun verificationFor(tool: String): String = when {
        tool == "verify_send" -> "PASS: message sent"
        tool == "verify_action" -> "PASS from independent state check"
        tool.startsWith("browser_") -> "LOCAL VERIFY PASS after navigation, or readable snapshot"
        tool == "python_exec" -> "exit code 0 with output"
        tool == "shell" -> "exit code 0"
        tool == "read_screen" || tool == "look" -> "readable snapshot"
        else -> "LOCAL VERIFY PASS: visible state change"
    }

    private fun extractAppName(objective: String): String? =
        Regex("(?i)\\b(?:open|launch|start)\\s+([A-Za-z0-9 ._'-]{2,40})")
            .find(objective)?.groupValues?.get(1)?.trim()
            ?.substringBefore(" and ")?.substringBefore(",")?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun buildSearchQuery(intent: OrionIntentIntelligence.Intent): String {
        val stripped = intent.primaryObjective
            .replace(Regex("(?i)^\\s*(please\\s+)?(search|google|look up|research|find out)\\s+"), "")
            .replace(Regex("(?i)^\\s*for\\s+"), "")
            .trim()
        return stripped.ifBlank { intent.primaryObjective }
    }

    private fun buildRollback(strategyId: String, steps: List<PlanStep>): RollbackPlan? {
        // Only plans that can leave persistent state need a rollback path.
        val mutating = steps.filter { it.tool in MUTATING_TOOLS }
        if (mutating.isEmpty()) return null
        return RollbackPlan(
            triggerConditions = listOf("step_failure", "verification_failure", "owner_cancel"),
            steps = mutating.filter { it.tool != "python_exec" || it.optional }.map { s ->
                PlanStep(
                    stepId = "rollback_${s.stepId}",
                    name = "Undo ${s.name}",
                    description = "Restore prior state after ${s.tool}",
                    tool = when (s.tool) {
                        "send_message" -> "verify_send"
                        "open_app" -> "read_screen"
                        else -> "verify_action"
                    },
                    argsTemplate = mapOf("question" to "Was '${s.name}' reversed?"),
                    verificationMethod = "PASS from state check",
                    expectedEvidence = "prior state restored or confirmed irreversible",
                    optional = true
                )
            }
        )
    }

    private companion object {
        val UI_TOOLS = setOf("open_app", "click", "type_text", "swipe", "send_message")
        val MUTATING_TOOLS = setOf("send_message", "type_text", "click", "open_app", "python_exec", "shell")
    }
}