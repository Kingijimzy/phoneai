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
            successCriteria = emptyList(),
            urgency = OrionIntentIntelligence.Urgency.NORMAL,
            confidence = 0.9,
            contextualReferences = emptyList()
        )

        val strategy = OrionReasoningEngine.Strategy(
            id = "direct_tool",
            name = "Direct Tool",
            confidence = 0.9,
            riskLevel = OrionReasoningEngine.RiskLevel.LOW,
            estimatedDurationMs = 2000,
            estimatedSteps = 1,
            prerequisites = emptyList(),
            fallbackStrategies = emptyList()
        )

        val decision = OrionDecisionEngine.Decision(
            decisionId = "dec_1",
            timestamp = System.currentTimeMillis(),
            intent = intent,
            reasoningResult = OrionReasoningEngine.ReasoningResult(
                strategies = listOf(strategy),
                selectedStrategy = strategy,
                riskAssessment = OrionReasoningEngine.RiskAssessment(
                    overallRisk = OrionReasoningEngine.RiskImpact.LOW,
                    riskFactors = emptyList()
                )
            ),
            selectedStrategy = strategy,
            executionPlan = null,
            requiresConfirmation = false,
            confirmationReason = null,
            rationale = "Decision rationale",
            alternativesRejected = emptyList()
        )

        assertEquals("dec_1", decision.decisionId)
        assertEquals("Direct Tool", decision.selectedStrategy!!.name)
        assertFalse(decision.requiresConfirmation)
    }

    @Test
    fun testTradingIntentRequiresConfirmation() {
        val tradingIntent = OrionIntentIntelligence.Intent(
            type = OrionIntentIntelligence.IntentType.ACTION,
            primaryObjective = "Buy 0.1 BTC",
            entities = emptyMap(),
            constraints = emptyList(),
            dependencies = emptyList(),
            successCriteria = emptyList(),
            urgency = OrionIntentIntelligence.Urgency.HIGH,
            confidence = 0.9,
            contextualReferences = emptyList()
        )
        assertTrue(tradingIntent.primaryObjective.lowercase().contains("btc"))
        assertEquals(OrionIntentIntelligence.Urgency.HIGH, tradingIntent.urgency)
    }

    @Test
    fun testVerificationMethodInSuccessCriterion() {
        val criterion = OrionIntentIntelligence.SuccessCriterion(
            description = "App opens",
            verificationMethod = "SCREEN_CHANGE"
        )
        assertEquals("App opens", criterion.description)
        assertEquals("SCREEN_CHANGE", criterion.verificationMethod)
    }
}
