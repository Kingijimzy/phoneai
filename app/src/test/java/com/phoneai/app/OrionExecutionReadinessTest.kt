package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionExecutionReadinessTest {
    private fun tool(name: String, access: ToolAccess = ToolAccess.READ) =
        ToolDefinition(name, "$name {}", "test", access)

    private fun snapshot(vararg names: String) = OrionCapabilityDiscovery.discover(
        names.map { tool(it, if (it in setOf("connect_service", "shell", "termux_command")) ToolAccess.WRITE else ToolAccess.READ) },
        setOf("orion_core", "workspace_shell", "termux_bridge", "connected_services", "trading_research", "phone_control"),
        termuxReady = true
    )

    @Test fun greetingNeverSelectsTool() {
        val goal = OrionGoalUnderstanding.understand("wassup")
        val plan = OrionExecutionReadiness.plan(goal, snapshot("live_web_search"))
        assertEquals(OrionExecutionReadiness.NextStep.RESPOND, plan.nextStep)
        assertEquals(null, plan.recommendedTool)
    }

    @Test fun ambiguousAccountDoesNotStartConnection() {
        val goal = OrionGoalUnderstanding.understand("connect my account")
        val plan = OrionExecutionReadiness.plan(goal, snapshot("connect_service"))
        assertEquals(OrionExecutionReadiness.NextStep.CLARIFY, plan.nextStep)
    }

    @Test fun disconnectedGitHubRoutesToSecureSetupWhenAvailable() {
        val goal = OrionGoalUnderstanding.understand("connect my GitHub account")
        val integration = OrionIntegrationPlanner.Plan(
            OrionIntegrationPlanner.Provider.GITHUB,
            OrionIntegrationPlanner.State.AVAILABLE_NOT_CONNECTED,
            OrionIntegrationPlanner.NextStep.ASK_TO_CONNECT,
            listOf("github.account.read"), true, true, "not connected"
        )
        val plan = OrionExecutionReadiness.plan(goal, snapshot("connect_service"), integration)
        assertEquals(OrionExecutionReadiness.NextStep.OPEN_CONNECTION_SETUP, plan.nextStep)
        assertEquals("connect_service", plan.recommendedTool)
        assertTrue(plan.requiresApproval)
    }

    @Test fun unavailableShellNeverPromisesExecution() {
        val goal = OrionGoalUnderstanding.understand("run a command in Orion terminal")
        val plan = OrionExecutionReadiness.plan(goal, snapshot("live_web_search"))
        assertEquals(OrionExecutionReadiness.NextStep.PREPARE_SHELL_SETUP, plan.nextStep)
        assertFalse(plan.nextStep == OrionExecutionReadiness.NextStep.EXECUTE_SUPPORTED_TOOL)
    }

    @Test fun supportedBuiltInShellRequiresOutputVerification() {
        val goal = OrionGoalUnderstanding.understand("run a command in Orion terminal")
        val plan = OrionExecutionReadiness.plan(goal, snapshot("shell"))
        assertEquals(OrionExecutionReadiness.NextStep.EXECUTE_SUPPORTED_TOOL, plan.nextStep)
        assertEquals("shell", plan.recommendedTool)
        assertTrue(plan.verificationRequirement.contains("exit/result status"))
    }

    @Test fun builtInShellIsPreferredWhenBothBackendsExist() {
        val goal = OrionGoalUnderstanding.understand("run a command in Orion terminal")
        val plan = OrionExecutionReadiness.plan(goal, snapshot("shell", "termux_command"))
        assertEquals(OrionExecutionReadiness.NextStep.EXECUTE_SUPPORTED_TOOL, plan.nextStep)
        assertEquals("shell", plan.recommendedTool)
    }

    @Test fun unsupportedGoalReturnsConcreteGapInsteadOfSuccess() {
        val goal = OrionGoalUnderstanding.understand("help me do something unusual")
        val plan = OrionExecutionReadiness.plan(goal, snapshot())
        assertEquals(OrionExecutionReadiness.NextStep.RESEARCH_AND_PROPOSE, plan.nextStep)
        assertTrue(plan.reason.contains("No matching tool is currently available"))
    }
}
