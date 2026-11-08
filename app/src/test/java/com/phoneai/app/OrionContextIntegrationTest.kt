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
 * Tests for OrionContextIntegration - unified context for decision making.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionContextIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testScreenStates() {
        val states = OrionContextIntegration.DeviceContext.ScreenState.values()
        assertEquals(3, states.size)
        assertTrue(states.contains(OrionContextIntegration.DeviceContext.ScreenState.ON))
        assertTrue(states.contains(OrionContextIntegration.DeviceContext.ScreenState.OFF))
        assertTrue(states.contains(OrionContextIntegration.DeviceContext.ScreenState.UNKNOWN))
    }

    @Test
    fun testCapabilityHealthHasStatusString() {
        val health = OrionContextIntegration.CapabilityContext.CapabilityHealth(
            status = "HEALTHY",
            lastError = null,
            successRate = 1.0,
            avgLatencyMs = 100
        )
        assertEquals("HEALTHY", health.status)
        assertEquals(1.0, health.successRate, 0.001)
        assertEquals(100L, health.avgLatencyMs)
    }

    @Test
    fun testSessionDataConstruction() {
        val sessionData = OrionContextIntegration.SessionData(
            command = "test command",
            modality = "CHAT",
            activeObjective = "Test objective",
            sessionStart = System.currentTimeMillis()
        )
        assertEquals("test command", sessionData.command)
        assertEquals("CHAT", sessionData.modality)
        assertEquals("Test objective", sessionData.activeObjective)
    }

    @Test
    fun testUnifiedContextSummaryMethodExists() {
        val method = OrionContextIntegration::class.java.getDeclaredMethod("getContextSummary",
            OrionContextIntegration.UnifiedContext::class.java)
        assertEquals(String::class.java, method.returnType)
    }

    @Test
    fun testCondensedContextForPromptMethodExists() {
        val method = OrionContextIntegration::class.java.getDeclaredMethod("getCondensedContextForPrompt",
            OrionContextIntegration.UnifiedContext::class.java, Int::class.java)
        assertEquals(String::class.java, method.returnType)
    }
}
