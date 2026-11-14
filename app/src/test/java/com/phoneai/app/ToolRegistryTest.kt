package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolRegistryTest {
        @Test fun ultronCoordinationToolsAreAvailableBesideNativeExecutionTools() {
        val requestTools = ToolRegistry.nativeToolsFor("create a multi-step task and build a Python project")
            .map { it.name }.toSet()
        assertTrue(requestTools.containsAll(setOf(
            "chronos_awareness", "soul_state", "recall", "remember",
            "ask_agent", "queue_agent", "agent_jobs", "create_agent",
            "task_list", "task_add", "task_update",
            "python_exec", "workspace_write", "workspace_read", "save_file"
        )))
        assertTrue("The combined tool window must remain under provider limits",
            requestTools.size <= 112)
    }

    @Test fun workspaceListIsRegisteredAndReadOnly() {
        val tool = ToolRegistry.all.single { it.name == "workspace_list" }
        assertEquals(ToolAccess.READ, tool.access)
        assertEquals("workspace_shell", AgentPolicy.packForTool("workspace_list"))
        assertTrue(OrionPythonCapabilityExecutor.supports("workspace_list"))
    }

    @Test fun workspaceFileMutationsAreRegisteredRoutedAndReversible() {
        listOf("workspace_write", "workspace_delete", "workspace_move").forEach { name ->
            assertEquals(ToolAccess.WRITE, ToolRegistry.access(name))
            assertEquals("workspace_shell", AgentPolicy.packForTool(name))
            assertEquals(OrionCapabilityAdapters.Backend.FILES, OrionCapabilityAdapters.route(name)?.backend)
            assertTrue(OrionRuntimeCapabilityExecutor.supports(OrionCapabilityAdapters.Backend.FILES, name))
        }
        assertTrue(ToolRegistry.find("workspace_delete")!!.promptLine.contains("journaled for conflict-safe undo"))
        assertTrue(ToolRegistry.find("workspace_move")!!.promptLine.contains("without overwriting"))
    }

    @Test fun savedGestureRoutinesAreExposedThroughTheNativePhoneDispatcher() {
        listOf("routine_run", "routine_resume").forEach { name ->
            assertEquals(ToolAccess.WRITE, ToolRegistry.access(name))
            assertEquals("phone_control", AgentPolicy.packForTool(name))
            assertTrue(ToolRegistry.requiresAccessibility(name))
            assertEquals(OrionCapabilityAdapters.Backend.DEVICE, OrionCapabilityAdapters.route(name)?.backend)
            assertTrue(OrionRuntimeCapabilityExecutor.supports(OrionCapabilityAdapters.Backend.DEVICE, name))
        }
        val selected = ToolRegistry.nativeToolsFor("resume routine Morning routine").map { it.name }.toSet()
        assertTrue(selected.containsAll(setOf("routine_run", "routine_resume")))
        assertTrue(OrionCommandIntentPolicy.requiresDirectActionRouting("resume routine Morning routine"))
    }

    @Test fun pointAndSketchToolsUseTheNativeAccessibilityDeviceExecutor() {
        listOf("point_at", "sketch_screen").forEach { name ->
            assertEquals(ToolAccess.WRITE, ToolRegistry.access(name))
            assertTrue(ToolRegistry.requiresAccessibility(name))
            assertEquals("phone_control", AgentPolicy.packForTool(name))
            assertEquals(OrionCapabilityAdapters.Backend.DEVICE, OrionCapabilityAdapters.route(name)?.backend)
            assertTrue(OrionDeviceCapabilityExecutor.supports(name))
            assertTrue(OrionRuntimeCapabilityExecutor.supports(OrionCapabilityAdapters.Backend.DEVICE, name))
        }
        val sketch = ToolRegistry.find("sketch_screen")!!.promptLine
        assertTrue(sketch.contains("circle"))
        assertTrue(sketch.contains("arrow"))
        assertTrue(sketch.contains("underline"))
        assertTrue(sketch.contains("multiple calls accumulate"))
        assertTrue(sketch.contains("independent timeouts"))
        assertTrue(sketch.contains("up to 12"))
        val guidance = ToolRegistry.promptSection("point at three objects and explain them")
        assertTrue(guidance.contains("accumulate in one explanation"))
        assertTrue(guidance.contains("Scrolling or changing apps clears stale annotations"))
    }

    @Test fun deviceLocationToolIsRegisteredAndMappedToPhoneControl() {
        assertTrue(ToolRegistry.all.any { it.name == "device_location" })
        assertEquals("phone_control", AgentPolicy.packForTool("device_location"))
    }

    @Test fun telegramInboxIsWriteBecauseItCanDispatchAuthenticatedOwnerInstructions() {
        assertEquals(ToolAccess.WRITE, ToolRegistry.access("telegram_inbox"))
        assertTrue(ToolRegistry.find("telegram_inbox")!!.promptLine.contains("shared coordinator"))
        assertEquals(OrionCapabilityAdapters.Backend.COMMUNICATION, OrionCapabilityAdapters.route("telegram_inbox")?.backend)
        assertTrue(OrionCommunicationCapabilityExecutor.supports("telegram_inbox"))
    }

    @Test fun registryNamesAreUniqueAndContainEveryRuntimeToolFamily() {
        val names = ToolRegistry.all.map { it.name }
        assertEquals("Each tool name must have exactly one definition.", names.size, names.toSet().size)
        assertTrue(names.containsAll(setOf(
            "read_screen", "point_at", "sketch_screen", "verify_send", "type", "click_text", "browser_snapshot",
            "browser_type", "weather", "bybit_public_ticker", "telegram_send",
            "mcp_call", "remember", "recall", "discussion_search", "decision_history", "task_delete", "ask_user", "termux_command", "trade_history_summary", "gaming_session_summary", "gaming_session_note_add", "gaming_focus_preset"
        )))
    }


    @Test fun mcpResourceAndPromptReadsAreRegisteredAndReachTheSharedExecutor() {
        val readTools = listOf("mcp_list_resources", "mcp_read_resource", "mcp_list_prompts", "mcp_get_prompt")
        readTools.forEach { name ->
            assertEquals("$name must be read-only", ToolAccess.READ, ToolRegistry.access(name))
            assertEquals("phone_control", AgentPolicy.packForTool(name))
            assertEquals(OrionCapabilityAdapters.Backend.MCP, OrionCapabilityAdapters.route(name)?.backend)
            assertTrue("MCP executor must implement $name", OrionMcpCapabilityExecutor.supports(name))
            assertTrue("Runtime owner must implement $name", OrionRuntimeCapabilityExecutor.supports(OrionCapabilityAdapters.Backend.MCP, name))
        }
        val prompt = ToolRegistry.promptSection("inspect MCP resources and prompt templates")
        readTools.forEach { name ->
            val signature = ToolRegistry.find(name)!!.promptLine.substringBefore(":")
            assertTrue("MCP read capability remains discoverable: $name", prompt.contains(signature))
        }
    }

    @Test fun tradingDeskToolsHaveOneConcreteOwnerAndSafeAccessClasses() {
        val readTools = listOf("trading_calendar", "trading_sessions", "trade_risk_check", "setup_scan", "strategy_backtest", "market_forecast", "market_conditions", "strategy_list")
        readTools.forEach {
            assertEquals("$it must be read-only", ToolAccess.READ, ToolRegistry.access(it))
            assertEquals("trading_research", AgentPolicy.packForTool(it))
            assertEquals(OrionCapabilityAdapters.Backend.TRADING_DESK, OrionCapabilityAdapters.route(it)?.backend)
            assertTrue(OrionRuntimeCapabilityExecutor.supports(OrionCapabilityAdapters.Backend.TRADING_DESK, it))
        }
        listOf("strategy_save", "strategy_record_result", "paper_trade", "trading_kill_switch").forEach {
            assertEquals("$it must require write policy", ToolAccess.WRITE, ToolRegistry.access(it))
            assertEquals("trading_research", AgentPolicy.packForTool(it))
            assertEquals(OrionCapabilityAdapters.Backend.TRADING_DESK, OrionCapabilityAdapters.route(it)?.backend)
            assertTrue(OrionRuntimeCapabilityExecutor.supports(OrionCapabilityAdapters.Backend.TRADING_DESK, it))
        }
    }

    @Test fun decisionRecordMutationIsNotExposedAsAnAgentTool() {
        assertNull(ToolRegistry.find("record_decision"))
        assertNull(AgentPolicy.packForTool("record_decision"))
    }

    @Test fun everyRegisteredToolMapsToAnExistingCapabilityPack() {
        val knownPacks = AgentPolicy.packs.map { it.id }.toSet()
        ToolRegistry.all.forEach { tool ->
            val pack = AgentPolicy.packForTool(tool.name)
            assertNotNull("Registered tool ${tool.name} has no capability-pack mapping", pack)
            assertTrue(
                "Tool ${tool.name} maps to an unknown capability pack: $pack",
                pack != null && knownPacks.contains(pack)
            )
        }
    }

    @Test fun corePhoneAndMessagingPacksAreAvailableByDefaultButOptionalBridgesStayOptIn() {
        assertTrue(AgentPolicy.defaultOn("phone_control"))
        assertTrue(AgentPolicy.defaultOn("messaging"))
        assertFalse(AgentPolicy.defaultOn("termux_bridge"))
        assertFalse(AgentPolicy.defaultOn("remote_computer"))
        assertFalse(AgentPolicy.defaultOn("proactive"))
    }

    @Test fun browserNavigationUsesTheInAppBrowserWithoutExternalBrowserPrerequisites() {
        val contract = ToolRegistry.all.single { it.name == "browser_navigate" }.promptLine.lowercase()
        assertTrue(contract.contains("built-in webview browser"))
        assertTrue(contract.contains("launch it in-app"))
        assertTrue(contract.contains("does not require chrome or termux"))
    }

    @Test fun screenInspectionAndAppControlUseAndroidAccessibilityService() {
        listOf("read_screen", "click", "click_text", "type", "tap", "verify_action", "look", "play", "open_app", "point_at", "sketch_screen")
            .forEach { assertTrue("$it must use Android AccessibilityService", ToolRegistry.requiresAccessibility(it)) }
        assertFalse(ToolRegistry.requiresAccessibility("browser_snapshot"))
        assertFalse(ToolRegistry.requiresAccessibility("bybit_public_ticker"))
        assertFalse(ToolRegistry.requiresAccessibility("weather"))
    }

    @Test fun coordinateToolsDocumentTheirRequiredSafetyOptIn() {
        assertTrue(ToolRegistry.find("validate_action")!!.promptLine.contains("allow_coordinates?"))
        assertTrue(ToolRegistry.find("tap")!!.promptLine.contains("allow_coordinates:true"))
        assertTrue(ToolRegistry.find("long_press")!!.promptLine.contains("allow_coordinates:true"))
        assertTrue(ToolRegistry.find("swipe")!!.promptLine.contains("allow_coordinates:true"))
    }

    @Test fun termuxCommandToolUsesTheTermuxBridgeWithoutReadOnlyAllowlist() {
        assertEquals(ToolAccess.WRITE, ToolRegistry.access("termux_command"))
        assertEquals("termux_bridge", AgentPolicy.packForTool("termux_command"))
        assertEquals(OrionCapabilityAdapters.Backend.SHELL, OrionCapabilityAdapters.route("termux_command")?.backend)
        assertTrue(OrionRuntimeCapabilityExecutor.supports(OrionCapabilityAdapters.Backend.SHELL, "termux_command"))
    }

    @Test fun promptCatalogIsGeneratedFromToolDefinitions() {
        val prompt = ToolRegistry.promptSection()
        assertTrue(prompt.startsWith("Tools:\n"))
        ToolRegistry.all.forEach { tool ->
            assertTrue("Prompt is missing ${tool.name}", prompt.contains("- " + tool.promptLine))
        }
        assertTrue(prompt.contains("Tool-use guidance:"))
        assertTrue(prompt.contains("SCREEN TARGET RULE"))
        assertTrue(prompt.contains("SHELL BACKEND RULE"))
        assertTrue(prompt.contains("LIVE RESEARCH RULE"))
        assertTrue(prompt.contains("DOWNLOAD SIZE RULE"))
        assertTrue(prompt.contains("GOAL COMPLETION RULE"))
        assertTrue(prompt.contains("ROUTE SELECTION AND CAPABILITY CREATION RULE"))
        assertTrue(prompt.contains("Do not pretend a Python extension can add Android framework"))
    }

    @Test fun toolAccessMetadataIsAvailableToTheScopeGuard() {
        assertEquals(ToolAccess.READ, ToolRegistry.access("discussion_search"))
        assertEquals("orion_core", AgentPolicy.packForTool("discussion_search"))
        assertTrue(OrionMemoryCapabilityExecutor.supports("discussion_search"))
        assertEquals(ToolAccess.READ, ToolRegistry.access("decision_history"))
        assertEquals("orion_core", AgentPolicy.packForTool("decision_history"))
        assertTrue(OrionMemoryCapabilityExecutor.supports("decision_history"))
        assertEquals(ToolAccess.READ, ToolRegistry.access("read_screen"))
        assertEquals("phone_control", AgentPolicy.packForTool("look"))
        assertEquals(ToolAccess.READ, ToolRegistry.access("verify_send"))
        assertEquals(ToolAccess.WRITE, ToolRegistry.access("type"))
        assertEquals(ToolAccess.WRITE, ToolRegistry.access("telegram_send"))
        assertEquals(ToolAccess.WRITE, ToolRegistry.access("termux_command"))
        assertFalse(ToolRegistry.find("termux_command")!!.promptLine.contains("read-only diagnostic"))
        assertEquals("termux_bridge", AgentPolicy.packForTool("termux_command"))
        assertEquals(ToolAccess.READ, ToolRegistry.access("trade_history_summary"))
        assertEquals("trading_research", AgentPolicy.packForTool("trade_history_summary"))
        assertEquals(ToolAccess.READ, ToolRegistry.access("gaming_session_summary"))
        assertEquals(ToolAccess.WRITE, ToolRegistry.access("gaming_session_note_add"))
        assertEquals(ToolAccess.WRITE, ToolRegistry.access("gaming_focus_preset"))
        assertEquals("gaming_hobby", AgentPolicy.packForTool("gaming_session_summary"))
        assertEquals("gaming_hobby", AgentPolicy.packForTool("gaming_session_note_add"))
        assertEquals("gaming_hobby", AgentPolicy.packForTool("gaming_focus_preset"))
        assertNull(ToolRegistry.find("made_up_tool"))

        assertNull(AgentHooks.scopeBlockReason(
            "Message Amma on WhatsApp",
            "click_text",
            JSONObject().put("text", "Send")
        ))
        assertNotNull(AgentHooks.scopeBlockReason(
            "Open WhatsApp and draft a message",
            "click_text",
            JSONObject().put("text", "Send")
        ))
        assertNotNull(AgentHooks.scopeBlockReason(
            "Do this",
            "made_up_tool",
            JSONObject()
        ))
    }
}

class ToolRegistryPromptAvailabilityTest {
    @Test fun everyToolRemainsDiscoverableByNameAndArgumentSignature() {
        val prompt = ToolRegistry.promptSection("open WhatsApp and read my last chat")
        ToolRegistry.all.forEach { tool ->
            val endOfArguments = tool.promptLine.indexOf("}:")
            val signature = if (endOfArguments >= 0) tool.promptLine.substring(0, endOfArguments + 1)
                else tool.promptLine.substringBefore(":")
            assertTrue("Missing discoverable signature for ${tool.name}", prompt.contains(signature))
        }
    }

    @Test fun naturalLanguageDoesNotSelectToolsThroughHardcodedKeywordFamilies() {
        val launch = ToolRegistry.detailedToolNames("Open WhatsApp and find the right conversation")
        val research = ToolRegistry.detailedToolNames("Investigate a current event and compare evidence")
        val coding = ToolRegistry.detailedToolNames("Build a small Python scraper and run it")
        assertEquals("Natural-language wording must not change the hardcoded detail-selection set", launch, research)
        assertEquals(launch, coding)
        assertFalse("A natural-language phrase must not secretly route to a tool", launch.contains("open_app"))
        assertTrue("Core Python execution contract is always available", launch.contains("python_exec"))
        assertTrue("Core image generation contract is always available", launch.contains("image_generate"))
    }


    @Test fun extensionLifecycleContractsAreAlwaysDetailedForModelLedForgingAndReuse() {
        val detailed = ToolRegistry.detailedToolNames("an unrelated conversation")
        assertTrue(detailed.containsAll(setOf(
            "extension_list", "extension_create", "extension_update", "extension_test",
            "extension_enable", "extension_run", "extension_disable", "extension_history", "extension_rollback"
        )))
        assertTrue("The developer route must remain available beside extension forging",
            ToolRegistry.nativeToolsFor("an unrelated conversation").any { it.name == "dev_shell" })
    }

    @Test fun exactRegisteredToolNameCanRequestItsFullContract() {
        val selected = ToolRegistry.detailedToolNames("Please call open_app now")
        assertTrue(selected.contains("open_app"))
        assertTrue(ToolRegistry.detailedToolNames("inspect MCP resources").contains("mcp_call").not())
    }

    @Test fun allToolContractsAndSignaturesRemainDiscoverableWithoutKeywordSelection() {
        val prompt = ToolRegistry.promptSection("an unrelated natural-language request")
        ToolRegistry.all.forEach { tool ->
            val signature = tool.promptLine.substringBefore(":")
            assertTrue("Missing signature for ${tool.name}", prompt.contains(signature))
        }
        assertTrue(prompt.contains("- " + ToolRegistry.find("python_exec")!!.promptLine))
        assertTrue(prompt.contains("- " + ToolRegistry.find("image_generate")!!.promptLine))
    }

    @Test fun baselineAndFullRegistrySnapshotsRemainAvailableForDiagnostics() {
        val prompt = ToolRegistry.promptSection()
        assertTrue(prompt.startsWith("Tools:\n"))
        ToolRegistry.all.forEach { tool -> assertTrue(prompt.contains("- " + tool.promptLine)) }
        assertTrue(prompt.contains("Tool-use guidance:"))
        assertTrue(prompt.contains("SCREEN TARGET RULE"))
        assertTrue(prompt.contains("GOAL COMPLETION RULE"))
        val compact = ToolRegistry.promptSection(null)
        assertTrue(compact.contains("Also available, signatures only"))
        assertTrue(compact.contains("task_list {}"))
    }

    @Test fun mcpDiscoveryGuidanceUsesOnlyRegisteredReadTools() {
        val prompt = ToolRegistry.promptSection("Check my MCP servers and discover API Anything tools").lowercase()
        assertTrue(prompt.contains("mcp_status"))
        assertTrue(prompt.contains("mcp_list_tools"))
        assertTrue(prompt.contains("show configured mcp endpoint"))
        assertTrue(prompt.contains("mcp_list_tools {}"))
        assertTrue(prompt.contains("do not invent mcp_servers_list"))
        assertTrue(prompt.contains("listing tools is read-only"))
        assertEquals(ToolAccess.READ, ToolRegistry.access("mcp_status"))
        assertEquals(ToolAccess.READ, ToolRegistry.access("mcp_list_tools"))
    }
}



class ToolRegistryNativeSelectionTest {
    @Test fun currentRequestToolsCannotBeCrowdedOutByHistoricalToolNames() {
        val stale = ToolRegistry.names().take(18).toSet()
        val selected = ToolRegistry.nativeToolsFor("Open WhatsApp and inspect the screen", stale).map { it.name }.toSet()
        assertTrue(selected.contains("open_app"))
        assertTrue(selected.contains("read_screen"))
    }

    @Test fun nativeCallableFoundationDoesNotChangeWithUserKeywords() {
        val ordinary = ToolRegistry.nativeToolsFor("hello").map { it.name }.toSet()
        val unrelated = ToolRegistry.nativeToolsFor("weather forex whatsapp github python").map { it.name }.toSet()
        assertEquals("User vocabulary must not alter native tool routing.", ordinary, unrelated)
        assertTrue(ordinary.containsAll(setOf(
            "capability_search", "read_screen", "click", "swipe", "scroll",
            "python_exec", "workspace_write", "mcp_list_tools", "task_add"
        )))
        assertTrue("Broad specialist tools are always callable.", ordinary.contains("image_generate") && ordinary.contains("weather"))
        assertFalse(ordinary.contains("bybit_public_ticker"))
    }

    @Test fun recentToolContextUsesOnlyTheLatestAssistantCallGroup() {
        val history = org.json.JSONArray()
            .put(org.json.JSONObject().put("role", "assistant").put("tool_calls", org.json.JSONArray().put(
                org.json.JSONObject().put("function", org.json.JSONObject().put("name", "mcp_list_tools"))
            )))
            .put(org.json.JSONObject().put("role", "tool").put("name", "mcp_list_tools"))
            .put(org.json.JSONObject().put("role", "assistant").put("content", "Finished MCP inspection."))
            .put(org.json.JSONObject().put("role", "user").put("content", "Now open WhatsApp."))
        assertTrue(ToolRegistry.recentToolNames(history).isEmpty())

        history.put(org.json.JSONObject().put("role", "assistant").put("tool_calls", org.json.JSONArray()
            .put(org.json.JSONObject().put("function", org.json.JSONObject().put("name", "open_app")))
            .put(org.json.JSONObject().put("function", org.json.JSONObject().put("name", "lookup_app")))))
        assertEquals(setOf("open_app", "lookup_app"), ToolRegistry.recentToolNames(history))
    }

    @Test fun userVisibleSaveToolIsAlwaysFullyExposedToTheModel() {
        assertTrue(ToolRegistry.detailedToolNames("an unrelated phone request").contains("save_file"))
        assertTrue(ToolRegistry.promptSection("write a Python calculator").contains("USER-VISIBLE FILE SAVE RULE"))
        assertTrue(ToolRegistry.nativeToolsFor("Write a Python calculator and save it as a file")
            .any { it.name == "save_file" })
    }

    @Test fun pythonTasksExposeNativeRuntimeAndFileToolsTogether() {
        val selected = ToolRegistry.nativeToolsFor(
            "Write a Python calculator, save it as a file, execute it and test it."
        ).map { it.name }.toSet()
        assertTrue(selected.containsAll(setOf(
            "python_exec", "workspace_write", "workspace_read", "workspace_list", "save_file"
        )))
    }

    @Test fun unknownHistoricalToolNamesAreIgnored() {
        val history = org.json.JSONArray().put(org.json.JSONObject().put("role", "assistant").put("tool_calls", org.json.JSONArray()
            .put(org.json.JSONObject().put("function", org.json.JSONObject().put("name", "invented_tool")))
            .put(org.json.JSONObject().put("function", org.json.JSONObject().put("name", "device_info")))))
        assertEquals(setOf("device_info"), ToolRegistry.recentToolNames(history))
    }

    @Test fun combinedRequestsExposeCompletePhoneMcpAndDeveloperFamilies() {
        val selected = ToolRegistry.nativeToolsFor("Inspect MCP servers, use the phone, and build a Python project")
            .map { it.name }.toSet()
        val required = setOf(
            "mcp_status", "mcp_list_tools", "mcp_call", "mcp_list_resources",
            "open_app", "read_screen", "click", "type", "system_intent",
            "python_exec", "workspace_read", "workspace_write", "dev_python", "dev_shell"
        )
        assertTrue("Combined task must expose all relevant tool families", selected.containsAll(required))
    }

    @Test fun objectiveCompletionIsRegisteredAndEvidenceGated() {
        val tool = ToolRegistry.find("complete_objective")
        assertNotNull("objective completion must be a real registered tool", tool)
        assertEquals(ToolAccess.WRITE, ToolRegistry.access("complete_objective"))
        assertTrue(OrionTasksCapabilityExecutor.supports("complete_objective"))
        assertEquals(OrionCapabilityAdapters.Backend.TASKS, OrionCapabilityAdapters.route("complete_objective")?.backend)
        assertTrue(OrionRuntimeCapabilityExecutor.supports(OrionCapabilityAdapters.Backend.TASKS, "complete_objective"))
        assertTrue(tool!!.promptLine.contains("latest trusted successful executor receipt"))
        val selected = ToolRegistry.nativeToolsFor("complete the verified objective using its evidence receipt")
            .map { it.name }.toSet()
        assertTrue("objective completion must be discoverable in the native tool window", selected.contains("complete_objective"))
    }


    @Test fun validatesRequiredTypesAndUnknownArgumentsAtRuntime() {
        val tool = ToolDefinition("demo", "demo {name:string, enabled:boolean, count?:integer}: test", "test", ToolAccess.WRITE)
        assertNull(OrionToolContractCodec.validateArguments(tool, org.json.JSONObject().put("name", "ok").put("enabled", true)))
        assertTrue(OrionToolContractCodec.validateArguments(tool, org.json.JSONObject().put("enabled", true))!!.contains("missing required"))
        assertTrue(OrionToolContractCodec.validateArguments(tool, org.json.JSONObject().put("name", "ok").put("enabled", "true"))!!.contains("must be boolean"))
        assertTrue(OrionToolContractCodec.validateArguments(tool, org.json.JSONObject().put("name", "ok").put("enabled", true).put("unexpected", "x"))!!.contains("unknown argument"))
    }

    @Test fun untypedJsonArgumentsRemainCompatibleWithExistingToolContracts() {
        val tool = ToolDefinition("mcp_call", "mcp_call {name, arguments: json object}: call a paired MCP tool", "call", ToolAccess.WRITE)
        assertNull(OrionToolContractCodec.validateArguments(tool, org.json.JSONObject().put("name", "read_status").put("arguments", org.json.JSONObject().put("detail", "full"))))
    }

    @Test fun validatesNestedOpenJsonObjectsInsideExtensionTestArrays() {
        val tool = ToolDefinition(
            "extension_test",
            "extension_test {id, tests:[{input:{...}, expect:<json>}]}: test extension source",
            "test",
            ToolAccess.WRITE
        )
        val args = org.json.JSONObject().put("id", "sample").put("tests", org.json.JSONArray().put(
            org.json.JSONObject().put("input", org.json.JSONObject().put("value", 2)).put("expect", 4)
        ))
        assertNull(OrionToolContractCodec.validateArguments(tool, args))
    }

    @Test fun derivesTypedSchemasFromToolSignatures() {
        val tool = ToolDefinition(
            "demo",
            "demo {name:string, enabled:boolean, count?:integer, mode:enum['fast'|'safe'], items:[{id:string}]}: test",
            "test",
            ToolAccess.READ
        )
        val schema = tool.inputSchema
        val properties = schema.getJSONObject("properties")
        assertEquals("string", properties.getJSONObject("name").getString("type"))
        assertEquals("boolean", properties.getJSONObject("enabled").getString("type"))
        assertEquals("integer", properties.getJSONObject("count").getString("type"))
        assertEquals("fast", properties.getJSONObject("mode").getJSONArray("enum").getString(0))
        val required = schema.getJSONArray("required").toString()
        assertTrue(required.contains("name"))
        assertTrue(required.contains("enabled"))
        assertTrue(!required.contains("count"))
        assertEquals(false, schema.getBoolean("additionalProperties"))
    }
    @Test fun capabilitySearchIsAlwaysCallableAndReadOnly() {
        val definition = ToolRegistry.find("capability_search") ?: error("capability_search must be registered")
        assertEquals(ToolAccess.READ, definition.access)
        assertEquals("orion_core", AgentPolicy.packForTool("capability_search"))
        assertEquals(OrionCapabilityAdapters.Backend.RUNTIME_STATE, OrionCapabilityAdapters.route("capability_search")?.backend)
        assertTrue(OrionRuntimeCapabilityExecutor.supports(OrionCapabilityAdapters.Backend.RUNTIME_STATE, "capability_search"))
        val foundation = ToolRegistry.nativeToolsFor("hello")
        assertTrue(foundation.any { it.name == "capability_search" })
        assertTrue(foundation.any { it.name == "capability_load" })
        assertEquals(ToolAccess.READ, ToolRegistry.find("capability_load")?.access)
    }

    @Test fun capabilitySearchReturnsSimpleCandidatesAndLoadReturnsTheExactSchema() {
        val result = JSONObject(ToolRegistry.searchCapabilities("inspect MCP resources", 8))
        val rows = result.getJSONArray("results")
        assertTrue(rows.length() > 0)
        val names = (0 until rows.length()).map { rows.getJSONObject(it).getString("name") }
        assertTrue(names.contains("mcp_list_resources"))
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val name = row.getString("name")
            val definition = ToolRegistry.find(name) ?: error("discovery returned unregistered tool $name")
            assertEquals(definition.access.name.lowercase(), row.getString("access"))
            assertFalse("Search should not flood the model with unused schemas", row.has("schema"))
            val route = OrionCapabilityAdapters.route(name) ?: error("discovery returned unrouted tool $name")
            assertTrue(OrionRuntimeCapabilityExecutor.supports(route.backend, name))
        }
    }

    @Test fun capabilitySearchPaginationKeepsZeroMatchToolsReachable() {
        val first = JSONObject(ToolRegistry.searchCapabilities("zzqvortexquux", 5, 0))
        assertEquals(5, first.getInt("count"))
        assertTrue(first.getBoolean("has_more"))
        assertEquals(0, first.getInt("offset"))
        val total = first.getInt("total")
        assertTrue(total > 5)
        val seen = mutableListOf<String>()
        val firstRows = first.getJSONArray("results")
        for (i in 0 until firstRows.length()) {
            assertFalse("Search results should stay compact", firstRows.getJSONObject(i).has("schema"))
            seen += firstRows.getJSONObject(i).getString("name")
        }
        var offset = first.getInt("next_offset")
        while (offset < total) {
            val page = JSONObject(ToolRegistry.searchCapabilities("zzqvortexquux", 5, offset))
            assertEquals(offset, page.getInt("offset"))
            val rows = page.getJSONArray("results")
            for (i in 0 until rows.length()) {
                val name = rows.getJSONObject(i).getString("name")
                assertFalse("Duplicate capability across pages: $name", seen.contains(name))
                seen += name
            }
            offset += rows.length()
        }
        assertEquals(total, seen.size)
        assertEquals(total, seen.toSet().size)
        val end = JSONObject(ToolRegistry.searchCapabilities("zzqvortexquux", 5, total))
        assertEquals(0, end.getInt("count"))
        assertFalse(end.getBoolean("has_more"))
    }

    @Test fun capabilitySearchPaginationIsDeterministicAndClampsBounds() {
        val query = "zzqvortexquux"
        val a = JSONObject(ToolRegistry.searchCapabilities(query, 4, 0))
        val b = JSONObject(ToolRegistry.searchCapabilities(query, 4, 0))
        assertEquals(a.getJSONArray("results").toString(), b.getJSONArray("results").toString())
        val clamped = JSONObject(ToolRegistry.searchCapabilities(query, 999, -20))
        assertEquals(12, clamped.getInt("limit"))
        assertEquals(0, clamped.getInt("offset"))
        assertEquals(minOf(12, clamped.getInt("total")), clamped.getInt("count"))
        val end = JSONObject(ToolRegistry.searchCapabilities(query, 5, clamped.getInt("total") + 100))
        assertEquals(clamped.getInt("total"), end.getInt("offset"))
        assertEquals(0, end.getInt("count"))
        assertFalse(end.getBoolean("has_more"))
    }

    @Test fun capabilityLoadExposesOnlyTheExactSelectedRegisteredTool() {
        val exposed = ToolRegistry.nativeToolsFor("hello").toMutableList()
        assertFalse(exposed.any { it.name == "trade_history_summary" })
        val before = exposed.size
        val search = JSONObject(ToolRegistry.searchCapabilities("trade_history_summary", 8))
        assertTrue(search.getJSONArray("results").length() > 0)
        assertEquals(before, exposed.size)
        val loaded = JSONObject(ToolRegistry.loadCapability(exposed, "trade_history_summary"))
        assertTrue(loaded.getBoolean("loaded"))
        assertEquals("object", loaded.getJSONObject("schema").getString("type"))
        assertFalse(loaded.getBoolean("already_callable"))
        assertEquals("trade_history_summary", loaded.getString("tool_name"))
        assertTrue(exposed.any { it.name == "trade_history_summary" })
        assertEquals(before + 1, exposed.size)
        val duplicate = JSONObject(ToolRegistry.loadCapability(exposed, "trade_history_summary"))
        assertTrue(duplicate.getBoolean("loaded"))
        assertTrue(duplicate.getBoolean("already_callable"))
        assertEquals(before + 1, exposed.size)
    }

    @Test fun capabilityLoadRejectsUnknownAndUnimplementedTools() {
        val exposed = ToolRegistry.nativeToolsFor("hello").toMutableList()
        val unknown = JSONObject(ToolRegistry.loadCapability(exposed, "invented_tool"))
        assertFalse(unknown.getBoolean("loaded"))
        assertEquals("unknown_registered_tool", unknown.getString("error"))
        val internal = JSONObject(ToolRegistry.loadCapability(exposed, "capability_search"))
        assertFalse(internal.getBoolean("loaded"))
        assertEquals("capability_not_implemented", internal.getString("error"))
    }

    @Test fun capabilityLoadRespectsProviderDeclarationLimit() {
        val exposed = ToolRegistry.nativeToolsFor("hello").toMutableList()
        val existingNames = exposed.map { it.name }.toSet()
        val candidate = ToolRegistry.all.firstOrNull { tool ->
            val route = OrionCapabilityAdapters.route(tool.name)
            tool.name !in existingNames && route != null && OrionRuntimeCapabilityExecutor.supports(route.backend, tool.name)
        } ?: error("expected at least one implemented capability outside the initial callable set")
        val fillers = ToolRegistry.all.filter { it.name != candidate.name && it.name !in existingNames }
        exposed.addAll(fillers.take(128 - exposed.size))
        assertEquals(128, exposed.size)
        val result = JSONObject(ToolRegistry.loadCapability(exposed, candidate.name))
        assertFalse(result.getBoolean("loaded"))
        assertEquals("provider_tool_declaration_limit", result.getString("error"))
    }

}
