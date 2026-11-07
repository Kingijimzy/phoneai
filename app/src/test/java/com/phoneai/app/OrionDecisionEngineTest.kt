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
 * Tests for OrionDecisionEngine - selecting strategies and explaining decisions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionDecisionEngineTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testDecisionConstruction() {
        val intent = OrionIntentIntelligence.Intent(
            type = OrionIntentIntelligence.IntentType.ACTION,
            primaryObjective = "Open WhatsApp",
            entities = mapOf("WhatsApp" to "APP"),
            constraints = emptyList(),
            dependencies = emptyList(),
            successCriteria = listOf(
                OrionIntentIntelligence.SuccessCriterion(
                    "App opens",
                    OrionIntentIntelligence.SuccessCriterion.VerificationMethod.SCREEN_CHANGE,
                    "WhatsApp visible"
                )
            ),
            urgency = OrionIntentIntelligence.Urgency.NORMAL,
            confidence = 0.9,
            contextualReferences = emptyList(),
            suggestedCapabilities = listOf("open_app")
        )

        val strategy = OrionReasoningEngine.Strategy(
            id = "direct_tool",
            name = "Direct Tool",
            description = "Open app directly",
            approach = OrionReasoningEngine.Strategy.Approach.DIRECT_TOOL,
            requiredCapabilities = setOf("open_app"),
            estimatedSteps = 1,
            estimatedDurationMs = 2000,
            riskLevel = OrionReasoningEngine.Strategy.RiskLevel.LOW,
            confidence = 0.9,
            pros = listOf("Fast"),
            cons = emptyList(),
            fallbackStrategies = emptyList(),
            prerequisites = emptyList()
        )

        val executionPlan = OrionDecisionEngine.ExecutionPlan(
            planId = "plan_1",
            strategyId = "direct_tool",
            steps = listOf(
                OrionDecisionEngine.ExecutionStep(
                    stepId = "step_1",
                    name = "Open WhatsApp",
                    description = "Open WhatsApp app",
                    tool = "open_app",
                    argsTemplate = JSONObject().put("name", "WhatsApp"),
                    capability = "open_app",
                    verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.SCREEN_CHANGE,
                    expectedEvidence = "WhatsApp opened",
                    dependsOn = emptyList(),
                    timeoutMs = 30000,
                    retryPolicy = OrionDecisionEngine.RetryPolicy(1, 1000, setOf("timeout"), null)
                )
            ),
            dependencies = emptyMap(),
            successConditions = listOf("WhatsApp visible"),
            rollbackPlan = null,
            estimatedTotalDurationMs = 30000
        )

        val decision = OrionDecisionEngine.Decision(
            decisionId = "dec_1",
            timestamp = System.currentTimeMillis(),
            intent = intent,
            reasoningResult = OrionReasoningEngine.ReasoningResult(
                intent = intent,
                strategies = listOf(strategy),
                selectedStrategy = strategy,
                decisionRationale = "Selected direct tool for simple action",
                riskAssessment = OrionReasoningEngine.RiskAssessment(
                    overallRisk = OrionReasoningEngine.Strategy.RiskLevel.LOW,
                    riskFactors = emptyList(),
                    mitigationStrategies = emptyList(),
                    requiresConfirmation = false,
                    confirmationReason = null
                ),
                alternativeConsidered = emptyList(),
                contextUsed = OrionReasoningEngine.ContextSummary(0, "", 0, 10, 0, 0)
            ),
            selectedStrategy = strategy,
            executionPlan = executionPlan,
            requiresConfirmation = false,
            confirmationReason = null,
            rationale = "Decision rationale",
            alternativesRejected = emptyList(),
            metadata = OrionDecisionEngine.DecisionMetadata(100, 1, "", emptyList())
        )

        assertEquals("dec_1", decision.decisionId)
        assertEquals("Direct Tool", decision.selectedStrategy.name)
        assertEquals(1, decision.executionPlan.steps.size)
        assertFalse(decision.requiresConfirmation)
    }

    @Test
    fun testRetryPolicyConstruction() {
        val policy = OrionDecisionEngine.RetryPolicy(
            maxRetries = 2,
            backoffMs = 2000,
            retryOn = setOf("timeout", "verification_failed"),
            alternativeStrategyOnFailure = "research_then_act"
        )

        assertEquals(2, policy.maxRetries)
        assertEquals(2000, policy.backoffMs)
        assertEquals(2, policy.retryOn.size)
        assertEquals("research_then_act", policy.alternativeStrategyOnFailure)
    }

    @Test
    fun testRollbackPlanConstruction() {
        val rollback = OrionDecisionEngine.RollbackPlan(
            steps = listOf(
                OrionDecisionEngine.RollbackStep(
                    stepId = "rollback_1",
                    tool = "file_delete",
                    args = JSONObject().put("path", "/tmp/test.txt"),
                    description = "Delete created file"
                )
            ),
            triggerConditions = listOf("verification_failed", "user_cancelled")
        )

        assertEquals(1, rollback.steps.size)
        assertEquals(2, rollback.triggerConditions.size)
    }

    @Test
    fun testVerificationMethods() {
        val methods = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.values()
        assertTrue(methods.size >= 8)
        
        val methodNames = methods.map { it.name }.toSet()
        assertTrue(methodNames.contains("SCREEN_CHANGE"))
        assertTrue(methodNames.contains("TOOL_OUTPUT"))
        assertTrue(methodNames.contains("FILE_EXISTS"))
        assertTrue(methodNames.contains("MESSAGE_SENT"))
        assertTrue(methodNames.contains("VERIFY_ACTION"))
        assertTrue(methodNames.contains("SHELL_EXIT_CODE"))
        assertTrue(methodNames.contains("MEMORY_RECORDED"))
        assertTrue(methodNames.contains("OBJECTIVE_COMPLETE"))
        assertTrue(methodNames.contains("EXTERNAL_API"))
    }
}
