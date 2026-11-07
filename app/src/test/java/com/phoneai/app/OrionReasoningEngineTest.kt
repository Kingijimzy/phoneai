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
    fun testStrategyApproachesDefined() {
        val approaches = OrionReasoningEngine.Strategy.Approach.values()
        assertTrue("Should have multiple approaches", approaches.size >= 5)
        
        val approachNames = approaches.map { it.name }.toSet()
        assertTrue("Should have DIRECT_TOOL", approachNames.contains("DIRECT_TOOL"))
        assertTrue("Should have SEQUENTIAL_STEPS", approachNames.contains("SEQUENTIAL_STEPS"))
        assertTrue("Should have PARALLEL_DELEGATION", approachNames.contains("PARALLEL_DELEGATION"))
        assertTrue("Should have RESEARCH_THEN_ACT", approachNames.contains("RESEARCH_THEN_ACT"))
        assertTrue("Should have CODE_EXECUTION", approachNames.contains("CODE_EXECUTION"))
    }

    @Test
    fun testRiskLevelsDefined() {
        val riskLevels = OrionReasoningEngine.Strategy.RiskLevel.values()
        assertEquals(5, riskLevels.size)
        assertTrue(riskLevels.contains(OrionReasoningEngine.Strategy.RiskLevel.MINIMAL))
        assertTrue(riskLevels.contains(OrionReasoningEngine.Strategy.RiskLevel.LOW))
        assertTrue(riskLevels.contains(OrionReasoningEngine.Strategy.RiskLevel.MEDIUM))
        assertTrue(riskLevels.contains(OrionReasoningEngine.Strategy.RiskLevel.HIGH))
        assertTrue(riskLevels.contains(OrionReasoningEngine.Strategy.RiskLevel.CRITICAL))
    }

    @Test
    fun testPrerequisiteTypes() {
        val types = OrionReasoningEngine.Prerequisite.PrerequisiteType.values()
        assertTrue(types.size >= 5)
        assertTrue(types.contains(OrionReasoningEngine.Prerequisite.PrerequisiteType.CAPABILITY))
        assertTrue(types.contains(OrionReasoningEngine.Prerequisite.PrerequisiteType.PERMISSION))
        assertTrue(types.contains(OrionReasoningEngine.Prerequisite.PrerequisiteType.STATE))
        assertTrue(types.contains(OrionReasoningEngine.Prerequisite.PrerequisiteType.DATA))
        assertTrue(types.contains(OrionReasoningEngine.Prerequisite.PrerequisiteType.RESOURCE))
    }

    @Test
    fun testRiskFactorLikelihoodImpact() {
        val likelihoods = OrionReasoningEngine.RiskFactor.Likelihood.values()
        assertEquals(5, likelihoods.size)
        
        val impacts = OrionReasoningEngine.RiskFactor.Impact.values()
        assertEquals(5, impacts.size)
    }

    @Test
    fun testStrategyConstruction() {
        val strategy = OrionReasoningEngine.Strategy(
            id = "test_1",
            name = "Test Strategy",
            description = "A test strategy",
            approach = OrionReasoningEngine.Strategy.Approach.DIRECT_TOOL,
            requiredCapabilities = setOf("open_app"),
            estimatedSteps = 1,
            estimatedDurationMs = 2000,
            riskLevel = OrionReasoningEngine.Strategy.RiskLevel.LOW,
            confidence = 0.9,
            pros = listOf("Fast", "Simple"),
            cons = listOf("Limited scope"),
            fallbackStrategies = listOf("sequential_steps"),
            prerequisites = listOf(
                OrionReasoningEngine.Prerequisite(
                    OrionReasoningEngine.Prerequisite.PrerequisiteType.CAPABILITY,
                    "open_app available",
                    true,
                    null
                )
            )
        )

        assertEquals("test_1", strategy.id)
        assertEquals("Test Strategy", strategy.name)
        assertEquals(OrionReasoningEngine.Strategy.Approach.DIRECT_TOOL, strategy.approach)
        assertEquals(1, strategy.estimatedSteps)
        assertEquals(0.9, strategy.confidence, 0.01)
    }
}
