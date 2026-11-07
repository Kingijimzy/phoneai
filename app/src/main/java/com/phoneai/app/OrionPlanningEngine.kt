package com.phoneai.app
import com.phoneai.app.OrionContextIntegration.DeviceContext

import org.json.JSONArray
import org.json.JSONObject

/**
 * Orion Planning Engine
 *
 * Generates executable objective graphs with dependencies and success conditions.
 * Integrates with OrionObjectiveGraph for persistence and execution tracking.
 */
class OrionPlanningEngine(
    private val svc: PhoneAiService,
    private val capabilityDiscovery: OrionCapabilityDiscovery,
    private val taskOrchestrator: OrionTaskOrchestrator,
    private val recall: OrionRecall,
    private val knowledgeGraph: OrionKnowledgeGraph
) {

    data class Plan(
        val planId: String,
        val objectiveId: String,
        val nodes: List<PlanNode>,
        val edges: List<PlanEdge>,
        val globalConstraints: List<GlobalConstraint>,
        val successConditions: List<String>,
        val estimatedDurationMs: Long,
        val riskLevel: RiskLevel,
        val metadata: PlanMetadata
    ) {
        enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }
    }

    data class PlanNode(
        val id: String,
        val objective: String,
        val tool: String,
        val argsTemplate: JSONObject,
        val capability: String,
        val verificationMethod: OrionIntentIntelligence.SuccessCriterion.VerificationMethod,
        val expectedEvidence: String,
        val status: NodeStatus = NodeStatus.PENDING,
        val dependencies: List<String> = emptyList(), // Node IDs this depends on
        val timeoutMs: Long = 60000,
        val retryPolicy: RetryPolicy = RetryPolicy(2, 2000, setOf("timeout", "verification_failed"), null),
        val isOptional: Boolean = false,
        val metadata: Map<String, String> = emptyMap()
    ) {
        enum class NodeStatus { PENDING, READY, RUNNING, COMPLETE, FAILED, SKIPPED, BLOCKED }
    }

    data class PlanEdge(
        val from: String,
        val to: String,
        val type: EdgeType = EdgeType.DEPENDENCY
    ) {
        enum class EdgeType { DEPENDENCY, PARALLEL_GROUP, CONDITIONAL }
    }

    data class GlobalConstraint(
        val type: ConstraintType,
        val description: String,
        val severity: Severity
    ) {
        enum class ConstraintType { TIME_BUDGET, RESOURCE_LIMIT, PARALLELISM_CAP, API_QUOTA, BATTERY_THRESHOLD }
        enum class Severity { HARD, SOFT }
    }

    data class RetryPolicy(
        val maxRetries: Int,
        val backoffMs: Long,
        val retryOn: Set<String>,
        val alternativeStrategyOnFailure: String?
    )

    data class PlanMetadata(
        val createdAt: Long = System.currentTimeMillis(),
        val createdBy: String = "OrionPlanningEngine",
        val intentType: OrionIntentIntelligence.IntentType = OrionIntentIntelligence.IntentType.UNKNOWN,
        val sourceIntent: String = "",
        val version: Int = 1,
        val parentPlanId: String? = null
    )

    /**
     * Create an executable plan from a decision.
     */
    fun createPlan(
        decision: OrionDecisionEngine.Decision,
        intent: OrionIntentIntelligence.Intent
    ): Plan {
        val planId = "plan_${System.currentTimeMillis()}_${Math.abs(intent.primaryObjective.hashCode())}"
        val objectiveId = "obj_${System.currentTimeMillis()}"

        // Generate plan nodes from execution plan
        val nodes = decision.executionPlan.steps.map { step ->
            PlanNode(
                id = step.stepId,
                objective = step.description,
                tool = step.tool,
                argsTemplate = step.argsTemplate,
                capability = step.capability,
                verificationMethod = step.verificationMethod,
                expectedEvidence = step.expectedEvidence,
                dependsOn = step.dependsOn,
                timeoutMs = step.timeoutMs,
                retryPolicy = step.retryPolicy,
                isOptional = step.isOptional
            )
        }

        // Build edges from dependencies
        val edges = mutableListOf<PlanEdge>()
        for (step in decision.executionPlan.steps) {
            for (dep in step.dependsOn) {
                edges.add(PlanEdge(from = dep, to = step.stepId))
            }
        }

        // Add parallel group edges for independent steps
        val independentSteps = decision.executionPlan.steps.filter { it.dependsOn.isEmpty() }
        if (independentSteps.size > 1) {
            val groupId = "parallel_group_${System.currentTimeMillis()}"
            for (step in independentSteps) {
                edges.add(PlanEdge(from = groupId, to = step.stepId, type = PlanEdge.EdgeType.PARALLEL_GROUP))
            }
        }

        // Global constraints
        val globalConstraints = buildGlobalConstraints(intent, decision)

        // Risk assessment
        val riskLevel = mapRiskLevel(decision.reasoningResult.riskAssessment.overallRisk)

        // Save to objective graph for persistence
        val graph = OrionObjectiveGraph.Graph(
            id = objectiveId,
            nodes = nodes.map { node ->
                OrionObjectiveGraph.Objective(
                    id = node.id,
                    objective = node.objective,
                    status = OrionObjectiveGraph.NodeStatus.PENDING,
                    dependencies = node.dependencies,
                    tool = node.tool,
                    argsTemplate = node.argsTemplate.toString(),
                    verificationMethod = node.verificationMethod.name,
                    expectedEvidence = node.expectedEvidence,
                    timeoutMs = node.timeoutMs,
                    retryPolicy = JSONObject().put("maxRetries", node.retryPolicy.maxRetries)
                        .put("backoffMs", node.retryPolicy.backoffMs)
                        .put("retryOn", JSONArray(node.retryPolicy.retryOn))
                        .put("alternativeStrategy", node.retryPolicy.alternativeStrategyOnFailure ?: "")
                        .toString(),
                    metadata = JSONObject(node.metadata).toString()
                )
            },
            edges = edges.map { OrionObjectiveGraph.Edge(it.from, it.to, it.type.name) },
            globalConstraints = globalConstraints.map { gc ->
                OrionObjectiveGraph.GlobalConstraint(gc.type.name, gc.description, gc.severity.name)
            },
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            status = OrionObjectiveGraph.GraphStatus.PLANNED
        )

        OrionObjectiveGraph.save(svc, graph)

        return Plan(
            planId = planId,
            objectiveId = objectiveId,
            nodes = nodes,
            edges = edges,
            globalConstraints = globalConstraints,
            successConditions = decision.executionPlan.successConditions,
            estimatedDurationMs = decision.executionPlan.estimatedTotalDurationMs,
            riskLevel = riskLevel,
            metadata = PlanMetadata(
                intentType = intent.type,
                sourceIntent = intent.primaryObjective
            )
        )
    }

    /**
     * Create a plan from a natural language objective (for direct planning).
     */
    fun createPlanFromObjective(
        objective: String,
        context: PlanningContext
    ): Plan {
        // Analyze intent
        val intent = OrionIntentIntelligence(svc, OrionEmbeddingProvider(svc), recall, OrionEntityIndex(svc), knowledgeGraph)
            .analyzeIntent(
                input = objective,
                conversationHistory = context.conversationHistory,
                activeObjective = context.activeObjective,
                deviceContext = context.deviceContext,
                availableCapabilities = context.availableCapabilities,
                missionHistory = context.missionHistory
            )

        // Reason about strategies
        val reasoningResult = OrionReasoningEngine(svc, capabilityDiscovery, taskOrchestrator, OrionRecoveryPlanner(svc), OrionProactiveEngine(svc), recall)
            .reason(intent)

        // Make decision
        val decision = OrionDecisionEngine(svc, 
            OrionReasoningEngine(svc, capabilityDiscovery, taskOrchestrator, OrionRecoveryPlanner(svc), OrionProactiveEngine(svc), recall),
            OrionIntentIntelligence(svc, OrionEmbeddingProvider(svc), recall, OrionEntityIndex(svc), knowledgeGraph),
            capabilityDiscovery,
            OrionPolicyEngine(svc)
        ).decide(intent)

        return createPlan(decision, intent)
    }

    /**
     * Update plan based on execution feedback.
     */
    fun updatePlan(
        plan: Plan,
        completedNodeId: String,
        result: String,
        success: Boolean,
        verificationPassed: Boolean
    ): Plan {
        val updatedNodes = plan.nodes.map { node ->
            if (node.id == completedNodeId) {
                node.copy(
                    status = if (success && verificationPassed) PlanNode.NodeStatus.COMPLETE
                    else if (!success) PlanNode.NodeStatus.FAILED
                    else PlanNode.NodeStatus.BLOCKED,
                    metadata = node.metadata + ("lastResult" to result) + ("verified" to verificationPassed.toString())
                )
            } else if (node.status == PlanNode.NodeStatus.PENDING && node.dependencies.all { dep ->
                plan.nodes.find { it.id == dep }?.status == PlanNode.NodeStatus.COMPLETE
            }) {
                node.copy(status = PlanNode.NodeStatus.READY)
            } else {
                node
            }
        }

        // Check for blocked nodes due to failed dependencies
        val finalNodes = updatedNodes.map { node ->
            if (node.status == PlanNode.NodeStatus.READY || node.status == PlanNode.NodeStatus.PENDING) {
                val failedDeps = node.dependencies.filter { dep ->
                    updatedNodes.find { it.id == dep }?.status == PlanNode.NodeStatus.FAILED
                }
                if (failedDeps.isNotEmpty()) {
                    node.copy(status = PlanNode.NodeStatus.BLOCKED, metadata = node.metadata + ("blockedBy" -> failedDeps.joinToString(",")))
                } else node
            } else node
        )

        // Recalculate ready nodes and constraints
        val newGlobalConstraints = plan.globalConstraints + buildDynamicConstraints(finalNodes)

        return plan.copy(
            nodes = finalNodes,
            globalConstraints = newGlobalConstraints,
            metadata = plan.metadata.copy(version = plan.metadata.version + 1)
        )
    }

    /**
     * Replan from a failure point.
     */
    fun replanFromFailure(
        plan: Plan,
        failedNodeId: String,
        failureReason: String,
        availableAlternatives: List<String>
    ): Plan {
        val failedNode = plan.nodes.find { it.id == failedNodeId }
            ?: throw IllegalArgumentException("Failed node not found: $failedNodeId")

        // Determine alternative approach
        val alternativeTool = availableAlternatives.firstOrNull()
            ?: failedNode.tool // Default to same tool for retry

        // Create replacement node
        val replacementNode = failedNode.copy(
            id = "${failedNodeId}_retry_${System.currentTimeMillis()}",
            tool = alternativeTool,
            argsTemplate = adaptArgsForTool(failedNode.argsTemplate, failedNode.tool, alternativeTool),
            capability = alternativeTool,
            verificationMethod = inferVerificationMethod(alternativeTool),
            status = PlanNode.NodeStatus.PENDING,
            dependencies = failedNode.dependencies,
            retryPolicy = RetryPolicy(1, 5000, setOf("timeout", "verification_failed"), null),
            metadata = failedNode.metadata + ("replacementFor" -> failedNodeId) + ("failureReason" -> failureReason)
        )

        // Add new edges for replacement
        val newEdges = plan.edges + failedNode.dependencies.map { dep ->
            PlanEdge(from = dep, to = replacementNode.id)
        }

        // Mark original as failed
        val updatedNodes = plan.nodes.map { node ->
            if (node.id == failedNodeId) node.copy(status = PlanNode.NodeStatus.FAILED) else node
        } + replacementNode

        return plan.copy(
            nodes = updatedNodes,
            edges = newEdges,
            metadata = plan.metadata.copy(version = plan.metadata.version + 1)
        )
    }

    private fun buildGlobalConstraints(
        intent: OrionIntentIntelligence.Intent,
        decision: OrionDecisionEngine.Decision
    ): List<GlobalConstraint> {
        val constraints = mutableListOf<GlobalConstraint>()

        // Time budget from urgency
        val timeBudget = when (intent.urgency) {
            OrionIntentIntelligence.Urgency.CRITICAL -> 30000L
            OrionIntentIntelligence.Urgency.HIGH -> 60000L
            OrionIntentIntelligence.Urgency.NORMAL -> 180000L
            OrionIntentIntelligence.Urgency.LOW -> 600000L
        }
        constraints.add(GlobalConstraint(
            GlobalConstraint.ConstraintType.TIME_BUDGET,
            "Total execution must complete within ${timeBudget / 1000}s",
            GlobalConstraint.Severity.HARD
        ))

        // Resource limit
        val autonomySettings = OrionAutonomySettings.read(svc)
        constraints.add(GlobalConstraint(
            GlobalConstraint.ConstraintType.RESOURCE_LIMIT,
            "Max AI requests: ${autonomySettings.maxAiRequests}",
            GlobalConstraint.Severity.HARD
        ))

        // Parallelism cap
        constraints.add(GlobalConstraint(
            GlobalConstraint.ConstraintType.PARALLELISM_CAP,
            "Max parallel agents: ${autonomySettings.maxParallelAgents}",
            GlobalConstraint.Severity.HARD
        ))

        // API quota
        constraints.add(GlobalConstraint(
            GlobalConstraint.ConstraintType.API_QUOTA,
            "Respect API rate limits for external services",
            GlobalConstraint.Severity.SOFT
        ))

        // Battery threshold
        val deviceContext = OrionDeviceContextProvider.summary(svc)
        deviceContext?.batteryLevel?.let { battery ->
            if (battery < 30) {
                constraints.add(GlobalConstraint(
                    GlobalConstraint.ConstraintType.BATTERY_THRESHOLD,
                    "Battery below 30% - prioritize critical tasks only",
                    GlobalConstraint.Severity.HARD
                ))
            }
        }

        // Hard constraints from intent
        for (constraint in intent.constraints.filter { it.severity == OrionIntentIntelligence.Constraint.Severity.HARD }) {
            constraints.add(GlobalConstraint(
                GlobalConstraint.ConstraintType.RESOURCE_LIMIT,
                constraint.description,
                GlobalConstraint.Severity.HARD
            ))
        }

        return constraints
    }

    private fun buildDynamicConstraints(nodes: List<PlanNode>): List<GlobalConstraint> {
        val constraints = mutableListOf<GlobalConstraint>()

        val failedCount = nodes.count { it.status == PlanNode.NodeStatus.FAILED }
        val blockedCount = nodes.count { it.status == PlanNode.NodeStatus.BLOCKED }
        val runningCount = nodes.count { it.status == PlanNode.NodeStatus.RUNNING }

        if (failedCount > 2) {
            constraints.add(GlobalConstraint(
                GlobalConstraint.ConstraintType.RESOURCE_LIMIT,
                "$failedCount nodes failed - consider replanning",
                GlobalConstraint.Severity.SOFT
            ))
        }

        if (blockedCount > 0) {
            constraints.add(GlobalConstraint(
                GlobalConstraint.ConstraintType.RESOURCE_LIMIT,
                "$blockedCount nodes blocked by failed dependencies",
                GlobalConstraint.Severity.HARD
            ))
        }

        if (runningCount > 3) {
            constraints.add(GlobalConstraint(
                GlobalConstraint.ConstraintType.PARALLELISM_CAP,
                "$runningCount concurrent executions - approaching limit",
                GlobalConstraint.Severity.SOFT
            ))
        }

        return constraints
    }

    private fun mapRiskLevel(reasoningRisk: OrionReasoningEngine.Strategy.RiskLevel): Plan.RiskLevel {
        return when (reasoningRisk) {
            OrionReasoningEngine.Strategy.RiskLevel.MINIMAL -> Plan.RiskLevel.LOW
            OrionReasoningEngine.Strategy.RiskLevel.LOW -> Plan.RiskLevel.LOW
            OrionReasoningEngine.Strategy.RiskLevel.MEDIUM -> Plan.RiskLevel.MEDIUM
            OrionReasoningEngine.Strategy.RiskLevel.HIGH -> Plan.RiskLevel.HIGH
            OrionReasoningEngine.Strategy.RiskLevel.CRITICAL -> Plan.RiskLevel.CRITICAL
        }
    }

    private fun adaptArgsForTool(
        originalArgs: JSONObject,
        fromTool: String,
        toTool: String
    ): JSONObject {
        // Adapt arguments when switching tools
        // This is a simplified version - in practice would be more sophisticated
        val adapted = JSONObject(originalArgs.toString())
        if (fromTool != toTool) {
            adapted.put("_adaptedFrom", fromTool)
            adapted.put("_adaptedTo", toTool)
        }
        return adapted
    }

    private fun inferVerificationMethod(tool: String): OrionIntentIntelligence.SuccessCriterion.VerificationMethod {
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
}

/**
 * Context for planning operations.
 */
data class PlanningContext(
    val conversationHistory: List<Pair<String, String>> = emptyList(),
    val activeObjective: String? = null,
    val deviceContext: DeviceContext? = null,
    val availableCapabilities: Set<String> = emptySet(),
    val missionHistory: List<OrionObjectiveGraph.MissionRecord> = emptyList(),
    val userPreferences: Map<String, String> = emptyMap()
)
