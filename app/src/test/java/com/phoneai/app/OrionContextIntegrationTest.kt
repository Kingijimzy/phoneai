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
        assertEquals(4, states.size)
        assertTrue(states.contains(OrionContextIntegration.DeviceContext.ScreenState.ON))
        assertTrue(states.contains(OrionContextIntegration.DeviceContext.ScreenState.OFF))
        assertTrue(states.contains(OrionContextIntegration.DeviceContext.ScreenState.AMBIENT))
        assertTrue(states.contains(OrionContextIntegration.DeviceContext.ScreenState.UNKNOWN))
    }

    @Test
    fun testCapabilityHealthStatuses() {
        val statuses = OrionContextIntegration.CapabilityContext.CapabilityHealth.Status.values()
        assertEquals(4, statuses.size)
        assertTrue(statuses.contains(OrionContextIntegration.CapabilityContext.CapabilityHealth.Status.HEALTHY))
        assertTrue(statuses.contains(OrionContextIntegration.CapabilityContext.CapabilityHealth.Status.DEGRADED))
        assertTrue(statuses.contains(OrionContextIntegration.CapabilityContext.CapabilityHealth.Status.UNHEALTHY))
        assertTrue(statuses.contains(OrionContextIntegration.CapabilityContext.CapabilityHealth.Status.UNKNOWN))
    }

    @Test
    fun testSessionDataConstruction() {
        val sessionData = OrionContextIntegration.SessionData(
            sessionId = "session_1",
            interactionTraceId = "trace_1",
            modality = "CHAT",
            conversationHistory = listOf("user" to "Hello", "assistant" to "Hi"),
            activeObjective = "Test objective",
            userPreferences = mapOf("persona" to "NEXUS"),
            activeSkills = listOf("skill_1"),
            budgetUsed = 5,
            budgetRemaining = 10,
            budgetMax = 15
        )

        assertEquals("session_1", sessionData.sessionId)
        assertEquals("trace_1", sessionData.interactionTraceId)
        assertEquals("CHAT", sessionData.modality)
        assertEquals(2, sessionData.conversationHistory.size)
        assertEquals("Test objective", sessionData.activeObjective)
        assertEquals(5, sessionData.budgetUsed)
        assertEquals(10, sessionData.budgetRemaining)
        assertEquals(15, sessionData.budgetMax)
    }

    @Test
    fun testUnifiedContextSummary() {
        // This test verifies the context summary method exists and returns a string
        // Full integration test would require all dependencies
        val method = OrionContextIntegration::class.java.getDeclaredMethod("getContextSummary", 
            OrionContextIntegration.UnifiedContext::class.java)
        assertEquals(String::class.java, method.returnType)
    }

    @Test
    fun testCondensedContextForPrompt() {
        val method = OrionContextIntegration::class.java.getDeclaredMethod("getCondensedContextForPrompt", 
            OrionContextIntegration.UnifiedContext::class.java, Int::class.java)
        assertEquals(String::class.java, method.returnType)
    }
}
