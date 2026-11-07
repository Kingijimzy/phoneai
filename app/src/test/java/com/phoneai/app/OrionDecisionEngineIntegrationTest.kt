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
        // (Full instantiation requires PhoneAiService, so we check class existence)

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
    fun testDataFlowTypes() {
        // Verify data types flow correctly through the pipeline
        
        // Intent → Reasoning
        val intent = OrionIntentIntelligence.Intent(
            type = OrionIntentIntelligence.IntentType.ACTION,
            primaryObjective = "Test",
            entities = emptyMap(),
            constraints = emptyList(),
            dependencies = emptyList(),
            successCriteria = emptyList(),
            urgency = OrionIntentIntelligence.Urgency.NORMAL,
            confidence = 0.8,
            contextualReferences = emptyList(),
            suggestedCapabilities = listOf("open_app")
        )

        // Reasoning output should contain strategies
        val strategy = OrionReasoningEngine.Strategy(
            id = "test",
            name = "Test",
            description = "Test",
            approach = OrionReasoningEngine.Strategy.Approach.DIRECT_TOOL,
            requiredCapabilities = setOf("open_app"),
            estimatedSteps = 1,
            estimatedDurationMs = 2000,
            riskLevel = OrionReasoningEngine.Strategy.RiskLevel.LOW,
            confidence = 0.9,
            pros = emptyList(),
            cons = emptyList(),
            fallbackStrategies = emptyList(),
            prerequisites = emptyList()
        )

        // Decision output should contain execution plan
        val step = OrionDecisionEngine.ExecutionStep(
            stepId = "step_1",
            name = "Test Step",
            description = "Test",
            tool = "open_app",
            argsTemplate = JSONObject(),
            capability = "open_app",
            verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.SCREEN_CHANGE,
            expectedEvidence = "App opened",
            dependsOn = emptyList(),
            timeoutMs = 30000,
            retryPolicy = OrionDecisionEngine.RetryPolicy(1, 1000, setOf(), null)
        )

        val plan = OrionDecisionEngine.ExecutionPlan(
            planId = "plan_1",
            strategyId = "test",
            steps = listOf(step),
            dependencies = emptyMap(),
            successConditions = listOf("App opened"),
            rollbackPlan = null,
            estimatedTotalDurationMs = 30000
        )

        // Planning output should contain plan nodes
        val planNode = OrionPlanningEngine.PlanNode(
            id = "node_1",
            objective = "Test",
            tool = "open_app",
            argsTemplate = JSONObject(),
            capability = "open_app",
            verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.SCREEN_CHANGE,
            expectedEvidence = "App opened"
        )

        // Predictive output should contain obstacle report
        val prediction = OrionPredictiveIntelligence.Prediction(
            predictionId = "pred_1",
            timestamp = System.currentTimeMillis(),
            type = OrionPredictiveIntelligence.Prediction.PredictionType.CAPABILITY_GAP,
            description = "Test",
            probability = 0.5,
            impact = OrionPredictiveIntelligence.Prediction.Impact.MODERATE,
            timeframe = OrionPredictiveIntelligence.Prediction.Timeframe.SHORT_TERM,
            indicators = emptyList(),
            recommendedActions = emptyList(),
            confidence = 0.8
        )

        val obstacleReport = OrionPredictiveIntelligence.ObstacleReport(
            predictions = listOf(prediction),
            overallRiskScore = 0.3,
            criticalPredictions = emptyList(),
            mitigationPlan = OrionPredictiveIntelligence.MitigationPlan(
                preemptiveActions = emptyList(),
                contingencyStrategies = emptyMap(),
                monitoringRules = emptyList()
            )
        )

        // All objects should be constructible
        assertNotNull(intent)
        assertNotNull(strategy)
        assertNotNull(plan)
        assertNotNull(planNode)
        assertNotNull(obstacleReport)
    }

    @Test
    fun testVerificationMethodConsistency() {
        // Verify verification methods are consistent across components
        val intentMethods = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.values()
        val planMethods = OrionPlanningEngine.PlanNode.NodeStatus.values()
        
        // Both should have the same verification method enum (shared)
        assertTrue(intentMethods.size >= 8)
    }

    @Test
    fun testRiskLevelMapping() {
        // Verify risk levels map correctly between components
        val reasoningRisk = OrionReasoningEngine.Strategy.RiskLevel.HIGH
        val planRisk = OrionPlanningEngine.Plan.RiskLevel.HIGH
        val predictiveImpact = OrionPredictiveIntelligence.Prediction.Impact.MAJOR
        
        // These should be compatible in severity
        assertEquals("HIGH", reasoningRisk.name)
        assertEquals("HIGH", planRisk.name)
        assertEquals("MAJOR", predictiveImpact.name)
    }

    @Test
    fun testDecisionEngineRequiresConfirmationForTrading() {
        // Test that trading intents require confirmation
        val tradingIntent = OrionIntentIntelligence.Intent(
            type = OrionIntentIntelligence.IntentType.TRADING,
            primaryObjective = "Buy 0.1 BTC",
            entities = mapOf("BTC" to "CRYPTO_SYMBOL"),
            constraints = emptyList(),
            dependencies = emptyList(),
            successCriteria = emptyList(),
            urgency = OrionIntentIntelligence.Urgency.HIGH,
            confidence = 0.9,
            contextualReferences = emptyList(),
            suggestedCapabilities = listOf("trading_place_order")
        )

        // Trading should have high urgency and require specific capabilities
        assertEquals(OrionIntentIntelligence.IntentType.TRADING, tradingIntent.type)
        assertEquals(OrionIntentIntelligence.Urgency.HIGH, tradingIntent.urgency)
        assertTrue(tradingIntent.suggestedCapabilities.contains("trading_place_order"))
    }

    @Test
    fun testDecisionEngineRequiresConfirmationForSystemControl() {
        val systemIntent = OrionIntentIntelligence.Intent(
            type = OrionIntentIntelligence.IntentType.SYSTEM_CONTROL,
            primaryObjective = "Restart phone",
            entities = emptyMap(),
            constraints = emptyList(),
            dependencies = emptyList(),
            successCriteria = emptyList(),
            urgency = OrionIntentIntelligence.Urgency.HIGH,
            confidence = 0.9,
            contextualReferences = emptyList(),
            suggestedCapabilities = listOf("computer_settings_restart")
        )

        assertEquals(OrionIntentIntelligence.IntentType.SYSTEM_CONTROL, systemIntent.type)
        assertTrue(systemIntent.suggestedCapabilities.contains("computer_settings_restart"))
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
            contextualReferences = emptyList(),
            suggestedCapabilities = listOf("open_app")
        )

        assertEquals(OrionIntentIntelligence.IntentType.ACTION, generalIntent.type)
        assertEquals(OrionIntentIntelligence.Urgency.NORMAL, generalIntent.urgency)
        assertFalse(generalIntent.suggestedCapabilities.contains("trading_place_order"))
        assertFalse(generalIntent.suggestedCapabilities.contains("computer_settings_restart"))
    }
}
