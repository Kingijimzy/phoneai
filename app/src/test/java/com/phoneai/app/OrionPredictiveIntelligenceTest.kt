package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for OrionPredictiveIntelligence - identifying obstacles and preparing alternatives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionPredictiveIntelligenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testPredictionTypes() {
        val types = OrionPredictiveIntelligence.Prediction.PredictionType.values()
        assertTrue(types.size >= 10)
        
        val typeNames = types.map { it.name }.toSet()
        assertTrue(typeNames.contains("CAPABILITY_GAP"))
        assertTrue(typeNames.contains("RESOURCE_EXHAUSTION"))
        assertTrue(typeNames.contains("PERMISSION_DENIED"))
        assertTrue(typeNames.contains("NETWORK_FAILURE"))
        assertTrue(typeNames.contains("EXECUTION_TIMEOUT"))
        assertTrue(typeNames.contains("VERIFICATION_FAILURE"))
        assertTrue(typeNames.contains("DEPENDENCY_BLOCK"))
        assertTrue(typeNames.contains("SKILL_OBSOLETE"))
        assertTrue(typeNames.contains("MACRO_STALE"))
        assertTrue(typeNames.contains("DEVICE_STATE_CHANGE"))
    }

    @Test
    fun testImpactLevels() {
        val impacts = OrionPredictiveIntelligence.Prediction.Impact.values()
        assertEquals(5, impacts.size)
        assertTrue(impacts.contains(OrionPredictiveIntelligence.Prediction.Impact.NEGLIGIBLE))
        assertTrue(impacts.contains(OrionPredictiveIntelligence.Prediction.Impact.MINOR))
        assertTrue(impacts.contains(OrionPredictiveIntelligence.Prediction.Impact.MODERATE))
        assertTrue(impacts.contains(OrionPredictiveIntelligence.Prediction.Impact.MAJOR))
        assertTrue(impacts.contains(OrionPredictiveIntelligence.Prediction.Impact.CATASTROPHIC))
    }

    @Test
    fun testTimeframes() {
        val timeframes = OrionPredictiveIntelligence.Prediction.Timeframe.values()
        assertEquals(4, timeframes.size)
        assertTrue(timeframes.contains(OrionPredictiveIntelligence.Prediction.Timeframe.IMMEDIATE))
        assertTrue(timeframes.contains(OrionPredictiveIntelligence.Prediction.Timeframe.SHORT_TERM))
        assertTrue(timeframes.contains(OrionPredictiveIntelligence.Prediction.Timeframe.MEDIUM_TERM))
        assertTrue(timeframes.contains(OrionPredictiveIntelligence.Prediction.Timeframe.LONG_TERM))
    }

    @Test
    fun testRecommendedActionPriorities() {
        val priorities = OrionPredictiveIntelligence.RecommendedAction.Priority.values()
        assertEquals(4, priorities.size)
        assertTrue(priorities.contains(OrionPredictiveIntelligence.RecommendedAction.Priority.CRITICAL))
        assertTrue(priorities.contains(OrionPredictiveIntelligence.RecommendedAction.Priority.HIGH))
        assertTrue(priorities.contains(OrionPredictiveIntelligence.RecommendedAction.Priority.MEDIUM))
        assertTrue(priorities.contains(OrionPredictiveIntelligence.RecommendedAction.Priority.LOW))
    }

    @Test
    fun testPredictionConstruction() {
        val prediction = OrionPredictiveIntelligence.Prediction(
            predictionId = "pred_1",
            timestamp = System.currentTimeMillis(),
            type = OrionPredictiveIntelligence.Prediction.PredictionType.CAPABILITY_GAP,
            description = "Missing capability: open_app",
            probability = 0.9,
            impact = OrionPredictiveIntelligence.Prediction.Impact.MAJOR,
            timeframe = OrionPredictiveIntelligence.Prediction.Timeframe.IMMEDIATE,
            indicators = listOf("Capability not in registry"),
            recommendedActions = listOf(
                OrionPredictiveIntelligence.RecommendedAction(
                    "acquire_capability",
                    "Use Prometheus to acquire",
                    OrionPredictiveIntelligence.RecommendedAction.Priority.HIGH,
                    OrionPredictiveIntelligence.RecommendedAction.Effort.MEDIUM
                )
            ),
            confidence = 0.95
        )

        assertEquals("pred_1", prediction.predictionId)
        assertEquals(OrionPredictiveIntelligence.Prediction.PredictionType.CAPABILITY_GAP, prediction.type)
        assertEquals(0.9, prediction.probability, 0.01)
        assertEquals(OrionPredictiveIntelligence.Prediction.Impact.MAJOR, prediction.impact)
        assertEquals(1, prediction.recommendedActions.size)
    }

    @Test
    fun testMitigationPlan() {
        val plan = OrionPredictiveIntelligence.MitigationPlan(
            preemptiveActions = listOf("[PRE] acquire_capability: Use Prometheus"),
            contingencyStrategies = mapOf("pred_1" to "acquire_capability"),
            monitoringRules = listOf(
                OrionPredictiveIntelligence.MonitoringRule(
                    ruleId = "monitor_1",
                    condition = "Capability gap detected",
                    checkIntervalMs = 5000,
                    actionOnTrigger = "Execute contingency: acquire_capability"
                )
            )
        )

        assertEquals(1, plan.preemptiveActions.size)
        assertEquals(1, plan.contingencyStrategies.size)
        assertEquals(1, plan.monitoringRules.size)
    }
}
