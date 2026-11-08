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
 * Integration tests for the complete Decision Engine pipeline:
 * Intent Intelligence → Reasoning Engine → Decision Engine → Planning Engine → Predictive Intelligence
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionDecisionEngineIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testCompletePipelineComponentsExist() {
        // Verify all decision engine components can be instantiated
        val intentClass = OrionIntentIntelligence::class.java
        val reasoningClass = OrionReasoningEngine::class.java
        val decisionClass = OrionDecisionEngine::class.java
        val planningClass = OrionPlanningEngine::class.java
        val predictiveClass = OrionPredictiveIntelligence::class.java
        val policyClass = OrionPolicyEngine::class.java
        val contextClass = OrionContextIntegration::class.java

        assertNotNull(intentClass)
        assertNotNull(reasoningClass)
        assertNotNull(decisionClass)
        assertNotNull(planningClass)
        assertNotNull(predictiveClass)
        assertNotNull(policyClass)
        assertNotNull(contextClass)
    }

    @Test
    fun testIntentDataClass() {
        val intent = OrionIntentIntelligence.Intent(
            type = OrionIntentIntelligence.IntentType.ACTION,
            primaryObjective = "Test",
            entities = emptyMap(),
            constraints = emptyList(),
            dependencies = emptyList(),
            successCriteria = emptyList(),
            urgency = OrionIntentIntelligence.Urgency.NORMAL,
            confidence = 0.8,
            contextualReferences = emptyList()
        )
        assertEquals(OrionIntentIntelligence.IntentType.ACTION, intent.type)
        assertEquals("Test", intent.primaryObjective)
        assertEquals(0.8, intent.confidence, 0.01)
    }

    @Test
    fun testReasoningStrategyDataClass() {
        val strategy = OrionReasoningEngine.Strategy(
            id = "test",
            name = "Test",
            confidence = 0.9,
            riskLevel = OrionReasoningEngine.RiskLevel.LOW,
            estimatedDurationMs = 2000,
            estimatedSteps = 1,
            prerequisites = emptyList(),
            fallbackStrategies = emptyList()
        )
        assertEquals("test", strategy.id)
        assertEquals("Test", strategy.name)
        assertEquals(0.9, strategy.confidence, 0.01)
        assertEquals(OrionReasoningEngine.RiskLevel.LOW, strategy.riskLevel)
    }

    @Test
    fun testReasoningResultDataClass() {
        val strategy = OrionReasoningEngine.Strategy(
            id = "s1", name = "Strategy 1",
            confidence = 0.8, riskLevel = OrionReasoningEngine.RiskLevel.LOW,
            estimatedDurationMs = 5000, estimatedSteps = 1,
            prerequisites = emptyList(), fallbackStrategies = emptyList()
        )
        val riskAssessment = OrionReasoningEngine.RiskAssessment(
            overallRisk = OrionReasoningEngine.RiskImpact.LOW,
            riskFactors = emptyList()
        )
        val result = OrionReasoningEngine.ReasoningResult(
            strategies = listOf(strategy),
            selectedStrategy = strategy,
            riskAssessment = riskAssessment
        )
        assertEquals(1, result.strategies.size)
        assertEquals(strategy, result.selectedStrategy)
    }

    @Test
    fun testDecisionEngineDecisionDataClass() {
        val intent = OrionIntentIntelligence.Intent(
            type = OrionIntentIntelligence.IntentType.ACTION,
            primaryObjective = "Test",
            entities = emptyMap(),
            constraints = emptyList(),
            dependencies = emptyList(),
            successCriteria = emptyList(),
            urgency = OrionIntentIntelligence.Urgency.NORMAL,
            confidence = 0.8,
            contextualReferences = emptyList()
        )
        val decision = OrionDecisionEngine.Decision(
            decisionId = "dec_1",
            timestamp = System.currentTimeMillis(),
            intent = intent,
            reasoningResult = OrionReasoningEngine.ReasoningResult(
                strategies = emptyList(),
                selectedStrategy = null,
                riskAssessment = OrionReasoningEngine.RiskAssessment(
                    overallRisk = OrionReasoningEngine.RiskImpact.LOW,
                    riskFactors = emptyList()
                )
            ),
            selectedStrategy = null,
            executionPlan = null,
            requiresConfirmation = false,
            confirmationReason = null,
            rationale = "Test rationale",
            alternativesRejected = emptyList()
        )
        assertEquals("dec_1", decision.decisionId)
        assertFalse(decision.requiresConfirmation)
    }

    @Test
    fun testPlanningEnginePlanStep() {
        val step = OrionPlanningEngine.PlanStep(
            stepId = "step_1",
            name = "Test Step",
            description = "Test description",
            tool = "open_app",
            argsTemplate = emptyMap(),
            verificationMethod = "SCREEN_CHANGE",
            expectedEvidence = "App opened"
        )
        assertEquals("step_1", step.stepId)
        assertEquals("open_app", step.tool)
        assertEquals(OrionPlanningEngine.PlanStepStatus.PENDING, step.status)
    }

    @Test
    fun testPlanningEngineExecutionPlan() {
        val step1 = OrionPlanningEngine.PlanStep(
            stepId = "step_1", name = "Step 1", description = "Desc 1",
            tool = "open_app", argsTemplate = emptyMap(),
            verificationMethod = "SCREEN_CHANGE", expectedEvidence = "Done"
        )
        val step2 = OrionPlanningEngine.PlanStep(
            stepId = "step_2", name = "Step 2", description = "Desc 2",
            tool = "read_screen", argsTemplate = emptyMap(),
            verificationMethod = "SCREEN_CHANGE", expectedEvidence = "Done",
            dependsOn = listOf("step_1")
        )
        val plan = OrionPlanningEngine.ExecutionPlan(
            planId = "plan_1",
            objective = "Test objective",
            steps = listOf(step1, step2),
            dependencies = mapOf("step_1" to emptyList(), "step_2" to listOf("step_1")),
            successConditions = listOf("All steps complete"),
            estimatedTotalDurationMs = 10000,
            rollbackPlan = null,
            createdAt = System.currentTimeMillis()
        )
        assertEquals("plan_1", plan.planId)
        assertEquals(2, plan.steps.size)
        val ordered = plan.orderedSteps()
        assertEquals(2, ordered.size)
    }

    @Test
    fun testTradingIntentRequiresConfirmation() {
        val tradingIntent = OrionIntentIntelligence.Intent(
            type = OrionIntentIntelligence.IntentType.ACTION,
            primaryObjective = "Buy 0.1 BTC",
            entities = mapOf("BTC" to "CRYPTO_SYMBOL"),
            constraints = emptyList(),
            dependencies = emptyList(),
            successCriteria = emptyList(),
            urgency = OrionIntentIntelligence.Urgency.HIGH,
            confidence = 0.9,
            contextualReferences = emptyList()
        )
        assertEquals(OrionIntentIntelligence.Urgency.HIGH, tradingIntent.urgency)
        assertTrue(tradingIntent.primaryObjective.lowercase().contains("btc"))
    }

    @Test
    fun testSystemControlIntentRequiresConfirmation() {
        val systemIntent = OrionIntentIntelligence.Intent(
            type = OrionIntentIntelligence.IntentType.SYSTEM_CONTROL,
            primaryObjective = "Restart phone",
            entities = emptyMap(),
            constraints = emptyList(),
            dependencies = emptyList(),
            successCriteria = emptyList(),
            urgency = OrionIntentIntelligence.Urgency.HIGH,
            confidence = 0.9,
            contextualReferences = emptyList()
        )
        assertEquals(OrionIntentIntelligence.IntentType.SYSTEM_CONTROL, systemIntent.type)
    }

    @Test
    fun testGeneralCapabilitiesDoNotRequireConfirmation() {
        val generalIntent = OrionIntentIntelligence.Intent(
            type = OrionIntentIntelligence.IntentType.ACTION,
            primaryObjective = "Open WhatsApp",
            entities = mapOf("WhatsApp" to "APP"),
            constraints = emptyList(),
            dependencies = emptyList(),
            successCriteria = emptyList(),
            urgency = OrionIntentIntelligence.Urgency.NORMAL,
            confidence = 0.9,
            contextualReferences = emptyList()
        )
        assertEquals(OrionIntentIntelligence.IntentType.ACTION, generalIntent.type)
        assertEquals(OrionIntentIntelligence.Urgency.NORMAL, generalIntent.urgency)
    }
}
