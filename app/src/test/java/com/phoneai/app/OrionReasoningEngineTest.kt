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
 * Tests for OrionReasoningEngine - evaluating approaches, constraints, capabilities, risks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionReasoningEngineTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testRiskLevelsDefined() {
        val riskLevels = OrionReasoningEngine.RiskLevel.values()
        assertTrue("Should have multiple risk levels", riskLevels.size >= 3)
        assertTrue(riskLevels.contains(OrionReasoningEngine.RiskLevel.LOW))
        assertTrue(riskLevels.contains(OrionReasoningEngine.RiskLevel.MEDIUM))
        assertTrue(riskLevels.contains(OrionReasoningEngine.RiskLevel.HIGH))
    }

    @Test
    fun testRiskImpactsDefined() {
        val impacts = OrionReasoningEngine.RiskImpact.values()
        assertTrue("Should have multiple risk impacts", impacts.size >= 4)
        assertTrue(impacts.contains(OrionReasoningEngine.RiskImpact.LOW))
        assertTrue(impacts.contains(OrionReasoningEngine.RiskImpact.MEDIUM))
        assertTrue(impacts.contains(OrionReasoningEngine.RiskImpact.HIGH))
        assertTrue(impacts.contains(OrionReasoningEngine.RiskImpact.CATASTROPHIC))
    }

    @Test
    fun testStrategyDataClass() {
        val strategy = OrionReasoningEngine.Strategy(
            id = "test_1",
            name = "Test Strategy",
            confidence = 0.9,
            riskLevel = OrionReasoningEngine.RiskLevel.LOW,
            estimatedDurationMs = 2000,
            estimatedSteps = 1,
            prerequisites = emptyList(),
            fallbackStrategies = emptyList()
        )
        assertEquals("test_1", strategy.id)
        assertEquals("Test Strategy", strategy.name)
        assertEquals(0.9, strategy.confidence, 0.01)
        assertEquals(OrionReasoningEngine.RiskLevel.LOW, strategy.riskLevel)
    }

    @Test
    fun testReasoningResultDataClass() {
        val strategy = OrionReasoningEngine.Strategy(
            id = "s1",
            name = "Strategy 1",
            confidence = 0.8,
            riskLevel = OrionReasoningEngine.RiskLevel.LOW,
            estimatedDurationMs = 5000,
            estimatedSteps = 1,
            prerequisites = emptyList(),
            fallbackStrategies = emptyList()
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
        assertEquals(OrionReasoningEngine.RiskImpact.LOW, result.riskAssessment.overallRisk)
    }
}
