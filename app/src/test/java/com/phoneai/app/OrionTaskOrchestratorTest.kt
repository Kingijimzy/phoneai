package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionTaskOrchestratorTest {
    private fun tool(name: String, access: ToolAccess = ToolAccess.READ) =
        ToolDefinition(name, "$name {}", "test", access)

    private val packs = setOf(
        "orion_core", "workspace_shell", "termux_bridge", "connected_services",
        "trading_research", "phone_control", "messaging"
    )

    @Test fun ordinaryConversationDoesNotInventAnAction() {
        val context = OrionTaskOrchestrator.build("hey", listOf(tool("weather")), packs, true)
        assertEquals(OrionGoalUnderstanding.Kind.CONVERSATION, context.goal.kind)
        assertEquals(OrionExecutionReadiness.NextStep.RESPOND, context.readiness.nextStep)
        assertEquals(null, context.readiness.recommendedTool)
    }

    @Test fun unavailableCapabilityProducesGapNotFalsePromise() {
        val context = OrionTaskOrchestrator.build(
            "run a command in Termux", listOf(tool("live_web_search")), packs, termuxReady = false
        )
        assertEquals(OrionExecutionReadiness.NextStep.PREPARE_SHELL_SETUP, context.readiness.nextStep)
        assertFalse(context.promptLines().any { it.contains("Task completed") })
        assertTrue(context.promptLines().any { it.contains("registered; 1 available") })
    }

    @Test fun missingPackCannotBeAdvertisedAsAvailable() {
        val context = OrionTaskOrchestrator.build(
            "check current weather", listOf(tool("weather")), emptySet(), termuxReady = true
        )
        assertEquals(0, context.capabilities.availableCount)
        assertTrue(context.promptLines().any { it.contains("weather: capability pack") })
    }

    @Test fun explicitAccountRequestCarriesConnectionFactsWithoutGrantingPermission() {
        val integration = OrionIntegrationPlanner.Plan(
            OrionIntegrationPlanner.Provider.GITHUB,
            OrionIntegrationPlanner.State.AVAILABLE_NOT_CONNECTED,
            OrionIntegrationPlanner.NextStep.ASK_TO_CONNECT,
            listOf("github.account.read"), true, true, "GitHub is not connected"
        )
        val context = OrionTaskOrchestrator.build(
            "connect my GitHub account", listOf(tool("connect_service", ToolAccess.WRITE)),
            packs, termuxReady = true, integration = integration
        )
        assertEquals(OrionExecutionReadiness.NextStep.OPEN_CONNECTION_SETUP, context.readiness.nextStep)
        assertTrue(context.promptLines().any { it.contains("state=AVAILABLE_NOT_CONNECTED") })
        assertTrue(context.readiness.requiresApproval)
    }

    @Test fun grantedCapabilityContractIsIncludedInTheActualPlannerContext() {
        val context = OrionTaskOrchestrator.build(
            "check current weather", listOf(tool("weather")), packs, termuxReady = true
        )
        val prompt = context.promptLines().joinToString("\n")
        assertTrue(prompt.contains("GRANTED CAPABILITY CONTRACT"))
        assertTrue(prompt.contains("use its documented functionality fully"))
        assertTrue(prompt.contains("Do not invent additional application-level restrictions"))
        assertTrue(prompt.contains("Actual Android permissions"))
        assertTrue(prompt.contains("EXECUTION ERROR HONESTY CONTRACT"))
        assertTrue(prompt.contains("is not proof permission is missing"))
    }

    @Test fun clarificationMustPrecedeDispatch() {
        val context = OrionTaskOrchestrator.build("connect my account", listOf(tool("connect_service", ToolAccess.WRITE)), packs, true)
        assertTrue(context.goal.needsClarification)
        assertTrue(context.promptLines().any { it.contains("ask one focused clarification") })
    }
}
