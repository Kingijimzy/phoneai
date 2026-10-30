package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrionCapabilityAdaptersTest {
    @Test fun everyRegisteredToolHasExactlyOneAdapterRoute() {
        val routes = OrionCapabilityAdapters.routes()
        assertEquals(ToolRegistry.all.size, routes.size)
        assertEquals(ToolRegistry.all.map { it.name }.toSet(), routes.map { it.tool }.toSet())
        assertEquals(routes.size, routes.map { it.tool }.toSet().size)
    }

    @Test fun executionFamiliesUseDedicatedAdapters() {
        val expected = mapOf(
            "shell" to OrionCapabilityAdapters.Backend.SHELL,
            "termux_command" to OrionCapabilityAdapters.Backend.SHELL,
            "python_exec" to OrionCapabilityAdapters.Backend.PYTHON,
            "extension_run" to OrionCapabilityAdapters.Backend.PYTHON,
            "workspace_write" to OrionCapabilityAdapters.Backend.FILES,
            "browser_navigate" to OrionCapabilityAdapters.Backend.BROWSER,
            "mcp_call" to OrionCapabilityAdapters.Backend.MCP,
            "click" to OrionCapabilityAdapters.Backend.DEVICE,
            "skills_list" to OrionCapabilityAdapters.Backend.SKILLS
        )
        expected.forEach { (tool, backend) ->
            assertEquals(tool, backend, OrionCapabilityAdapters.route(tool)?.backend)
        }
    }

    @Test fun unknownToolFailsClosedWithoutCallingExecutor() {
        var called = false
        val result = OrionCapabilityAdapters.dispatch("invented_tool", JSONObject()) { _, _ ->
            called = true
            "should not run"
        }
        assertTrue(result.startsWith("error:"))
        assertFalse(called)
    }

    @Test fun knownToolIsDispatchedThroughItsResolvedAdapterRoute() {
        val result = OrionCapabilityAdapters.dispatch("python_exec", JSONObject().put("code", "print(1)")) { route, args ->
            assertEquals(OrionCapabilityAdapters.Backend.PYTHON, route.backend)
            assertEquals("print(1)", args.getString("code"))
            "executed"
        }
        assertEquals("executed", result)
    }

    @Test fun adapterExceptionsBecomeExplicitFailures() {
        val result = OrionCapabilityAdapters.dispatch("mcp_call", JSONObject()) { _, _ ->
            error("offline")
        }
        assertTrue(result.startsWith("error: mcp adapter failed"))
    }

    @Test fun everyRegisteredToolHasAnExplicitBackendOwner() {
        assertEquals(ToolRegistry.all.map { it.name }.toSet(), OrionCapabilityAdapters.routes().map { it.tool }.toSet())
        assertTrue(OrionCapabilityAdapters.routes().none { it.backend == OrionCapabilityAdapters.Backend.CORE })
    }

    @Test fun integrationMemoryTaskResearchAndUtilityFamiliesAreRoutedExplicitly() {
        val expected = mapOf(
            "connect_service" to OrionCapabilityAdapters.Backend.INTEGRATIONS,
            "remember" to OrionCapabilityAdapters.Backend.MEMORY,
            "task_add" to OrionCapabilityAdapters.Backend.TASKS,
            "web_search" to OrionCapabilityAdapters.Backend.RESEARCH,
            "telegram_send" to OrionCapabilityAdapters.Backend.COMMUNICATION,
            "sms" to OrionCapabilityAdapters.Backend.COMMUNICATION,
            "notification_action" to OrionCapabilityAdapters.Backend.COMMUNICATION,
            "alarm" to OrionCapabilityAdapters.Backend.UTILITIES,
            "trade_history_summary" to OrionCapabilityAdapters.Backend.TRADING_READONLY,
            "gaming_session_summary" to OrionCapabilityAdapters.Backend.GAMING
        )
        expected.forEach { (tool, backend) ->
            assertEquals(tool, backend, OrionCapabilityAdapters.route(tool)?.backend)
        }
    }

    @Test fun everyDeviceAndMcpRouteHasAConcreteDedicatedExecutor() {
        val device = OrionCapabilityAdapters.routes()
            .filter { it.backend == OrionCapabilityAdapters.Backend.DEVICE }
        val mcp = OrionCapabilityAdapters.routes()
            .filter { it.backend == OrionCapabilityAdapters.Backend.MCP }

        assertTrue("Device backend must not be empty", device.isNotEmpty())
        assertTrue("MCP backend must not be empty", mcp.isNotEmpty())
        device.forEach { assertTrue("Missing device executor for ${it.tool}", OrionDeviceCapabilityExecutor.supports(it.tool)) }
        mcp.forEach { assertTrue("Missing MCP executor for ${it.tool}", OrionMcpCapabilityExecutor.supports(it.tool)) }
    }
    @Test fun everyIntegrationAndSkillRouteHasAConcreteDedicatedExecutor() {
        val integrations = OrionCapabilityAdapters.routes()
            .filter { it.backend == OrionCapabilityAdapters.Backend.INTEGRATIONS }
        val skills = OrionCapabilityAdapters.routes()
            .filter { it.backend == OrionCapabilityAdapters.Backend.SKILLS }

        assertTrue("Integrations backend must not be empty", integrations.isNotEmpty())
        assertTrue("Skills backend must not be empty", skills.isNotEmpty())
        integrations.forEach {
            assertTrue("Missing integrations executor for ${it.tool}", OrionIntegrationCapabilityExecutor.supports(it.tool))
        }
        skills.forEach {
            assertTrue("Missing skills executor for ${it.tool}", OrionSkillsCapabilityExecutor.supports(it.tool))
        }
    }

    @Test fun skillsExecutorValidatesManifestConstraints() {
        val valid = PhoneAiSkill(
            id = "test_skill",
            version = "1.0.0",
            description = "A bounded test skill.",
            allowedTools = setOf("read_screen"),
            maxCalls = 3,
            maxDurationMs = 10_000L,
            requiresApproval = true,
            enabled = false
        )
        assertTrue(OrionSkillsCapabilityExecutor.validate(valid).allowed)
        assertFalse(OrionSkillsCapabilityExecutor.validate(valid.copy(allowedTools = setOf("not_a_tool"))).allowed)
        assertFalse(OrionSkillsCapabilityExecutor.validate(valid.copy(maxCalls = 21)).allowed)
    }

    @Test fun everyResearchAndCommunicationRouteHasAConcreteDedicatedExecutor() {
        val research = OrionCapabilityAdapters.routes()
            .filter { it.backend == OrionCapabilityAdapters.Backend.RESEARCH }
        val communication = OrionCapabilityAdapters.routes()
            .filter { it.backend == OrionCapabilityAdapters.Backend.COMMUNICATION }

        assertTrue("Research backend must not be empty", research.isNotEmpty())
        assertTrue("Communication backend must not be empty", communication.isNotEmpty())
        research.forEach {
            assertTrue("Missing research executor for ${it.tool}", OrionResearchCapabilityExecutor.supports(it.tool))
        }
        communication.forEach {
            assertTrue("Missing communication executor for ${it.tool}", OrionCommunicationCapabilityExecutor.supports(it.tool))
        }
        assertFalse("Notification actions belong to communication, not device", 
            OrionCapabilityAdapters.routes().any {
                it.tool == "notification_action" && it.backend == OrionCapabilityAdapters.Backend.DEVICE
            })
    }


    @Test fun everyMemoryAndTasksRouteHasAConcreteDedicatedExecutor() {
        val memory = OrionCapabilityAdapters.routes()
            .filter { it.backend == OrionCapabilityAdapters.Backend.MEMORY }
        val tasks = OrionCapabilityAdapters.routes()
            .filter { it.backend == OrionCapabilityAdapters.Backend.TASKS }

        assertTrue("Memory backend must not be empty", memory.isNotEmpty())
        assertTrue("Tasks backend must not be empty", tasks.isNotEmpty())
        memory.forEach {
            assertTrue("Missing memory executor for ${it.tool}", OrionMemoryCapabilityExecutor.supports(it.tool))
        }
        tasks.forEach {
            assertTrue("Missing tasks executor for ${it.tool}", OrionTasksCapabilityExecutor.supports(it.tool))
        }
        assertEquals(OrionCapabilityAdapters.Backend.MEMORY, OrionCapabilityAdapters.route("undo_last")?.backend)
        assertEquals(OrionCapabilityAdapters.Backend.MEMORY, OrionCapabilityAdapters.route("undo_status")?.backend)
        assertEquals(OrionCapabilityAdapters.Backend.TASKS, OrionCapabilityAdapters.route("ask_user")?.backend)
    }


}
