package com.phoneai.app

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for OrionPlanningEngine - generating executable objective graphs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionPlanningEngineTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testPlanNodeStatuses() {
        val statuses = OrionPlanningEngine.PlanNode.NodeStatus.values()
        assertEquals(7, statuses.size)
        assertTrue(statuses.contains(OrionPlanningEngine.PlanNode.NodeStatus.PENDING))
        assertTrue(statuses.contains(OrionPlanningEngine.PlanNode.NodeStatus.READY))
        assertTrue(statuses.contains(OrionPlanningEngine.PlanNode.NodeStatus.RUNNING))
        assertTrue(statuses.contains(OrionPlanningEngine.PlanNode.NodeStatus.COMPLETE))
        assertTrue(statuses.contains(OrionPlanningEngine.PlanNode.NodeStatus.FAILED))
        assertTrue(statuses.contains(OrionPlanningEngine.PlanNode.NodeStatus.SKIPPED))
        assertTrue(statuses.contains(OrionPlanningEngine.PlanNode.NodeStatus.BLOCKED))
    }

    @Test
    fun testEdgeTypes() {
        val types = OrionPlanningEngine.PlanEdge.EdgeType.values()
        assertEquals(3, types.size)
        assertTrue(types.contains(OrionPlanningEngine.PlanEdge.EdgeType.DEPENDENCY))
        assertTrue(types.contains(OrionPlanningEngine.PlanEdge.EdgeType.PARALLEL_GROUP))
        assertTrue(types.contains(OrionPlanningEngine.PlanEdge.EdgeType.CONDITIONAL))
    }

    @Test
    fun testGlobalConstraintTypes() {
        val types = OrionPlanningEngine.GlobalConstraint.ConstraintType.values()
        assertEquals(5, types.size)
        assertTrue(types.contains(OrionPlanningEngine.GlobalConstraint.ConstraintType.TIME_BUDGET))
        assertTrue(types.contains(OrionPlanningEngine.GlobalConstraint.ConstraintType.RESOURCE_LIMIT))
        assertTrue(types.contains(OrionPlanningEngine.GlobalConstraint.ConstraintType.PARALLELISM_CAP))
        assertTrue(types.contains(OrionPlanningEngine.GlobalConstraint.ConstraintType.API_QUOTA))
        assertTrue(types.contains(OrionPlanningEngine.GlobalConstraint.ConstraintType.BATTERY_THRESHOLD))
    }

    @Test
    fun testPlanConstruction() {
        val plan = OrionPlanningEngine.Plan(
            planId = "plan_1",
            objectiveId = "obj_1",
            nodes = listOf(
                OrionPlanningEngine.PlanNode(
                    id = "step_1",
                    objective = "Open WhatsApp",
                    tool = "open_app",
                    argsTemplate = JSONObject().put("name", "WhatsApp"),
                    capability = "open_app",
                    verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.SCREEN_CHANGE,
                    expectedEvidence = "WhatsApp opened",
                    dependencies = emptyList(),
                    timeoutMs = 30000
                ),
                OrionPlanningEngine.PlanNode(
                    id = "step_2",
                    objective = "Send message",
                    tool = "send_message",
                    argsTemplate = JSONObject().put("contact", "Mom").put("message", "Hello"),
                    capability = "send_message",
                    verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.MESSAGE_SENT,
                    expectedEvidence = "Message sent",
                    dependencies = listOf("step_1"),
                    timeoutMs = 30000
                )
            ),
            edges = listOf(
                OrionPlanningEngine.PlanEdge("step_1", "step_2")
            ),
            globalConstraints = listOf(
                OrionPlanningEngine.GlobalConstraint(
                    OrionPlanningEngine.GlobalConstraint.ConstraintType.TIME_BUDGET,
                    "Complete within 60s",
                    OrionPlanningEngine.GlobalConstraint.Severity.HARD
                )
            ),
            successConditions = listOf("Message sent to Mom"),
            estimatedDurationMs = 60000,
            riskLevel = OrionPlanningEngine.Plan.RiskLevel.LOW,
            metadata = OrionPlanningEngine.PlanMetadata()
        )

        assertEquals(2, plan.nodes.size)
        assertEquals(1, plan.edges.size)
        assertEquals(1, plan.globalConstraints.size)
        assertEquals("step_1", plan.nodes[0].id)
        assertEquals("step_2", plan.nodes[1].id)
        assertEquals(listOf("step_1"), plan.nodes[1].dependencies)
    }

    @Test
    fun testRetryPolicyDefaults() {
        val policy = OrionPlanningEngine.RetryPolicy(
            maxRetries = 2,
            backoffMs = 2000,
            retryOn = setOf("timeout", "verification_failed"),
            alternativeStrategyOnFailure = null
        )

        assertEquals(2, policy.maxRetries)
        assertEquals(2000, policy.backoffMs)
        assertEquals(2, policy.retryOn.size)
        assertNull(policy.alternativeStrategyOnFailure)
    }

    @Test
    fun testRiskLevels() {
        val levels = OrionPlanningEngine.Plan.RiskLevel.values()
        assertEquals(4, levels.size)
        assertTrue(levels.contains(OrionPlanningEngine.Plan.RiskLevel.LOW))
        assertTrue(levels.contains(OrionPlanningEngine.Plan.RiskLevel.MEDIUM))
        assertTrue(levels.contains(OrionPlanningEngine.Plan.RiskLevel.HIGH))
        assertTrue(levels.contains(OrionPlanningEngine.Plan.RiskLevel.CRITICAL))
    }
}
