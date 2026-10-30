package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolRegistryTest {
    @Test fun deviceLocationToolIsRegisteredAndMappedToPhoneControl() {
        assertTrue(ToolRegistry.all.any { it.name == "device_location" })
        assertEquals("phone_control", AgentPolicy.packForTool("device_location"))
    }

    @Test fun registryNamesAreUniqueAndContainEveryRuntimeToolFamily() {
        val names = ToolRegistry.all.map { it.name }
        assertEquals("Each tool name must have exactly one definition.", names.size, names.toSet().size)
        assertTrue(names.containsAll(setOf(
            "read_screen", "verify_send", "type", "click_text", "browser_snapshot",
            "browser_type", "weather", "bybit_public_ticker", "telegram_send",
            "mcp_call", "remember", "recall", "discussion_search", "task_delete", "ask_user", "termux_command", "trade_history_summary", "gaming_session_summary", "gaming_session_note_add", "gaming_focus_preset"
        )))
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

    @Test fun screenInspectionAndAppControlRequireExplicitDisclosure() {
        listOf("read_screen", "click", "click_text", "type", "tap", "verify_action", "look", "play", "open_app")
            .forEach { assertTrue("$it must require screen-access consent", ToolRegistry.requiresAccessibility(it)) }
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

    @Test fun termuxAiCommandsAreRestrictedToExactReadOnlyDiagnostics() {
        assertTrue(TermuxCommandPolicy.isReadOnly("pwd"))
        assertTrue(TermuxCommandPolicy.isReadOnly("git status --short"))
        assertTrue(TermuxCommandPolicy.isReadOnly("python --version"))
        assertFalse(TermuxCommandPolicy.isReadOnly("pwd; rm -rf ~"))
        assertFalse(TermuxCommandPolicy.isReadOnly("git status; touch marker"))
        assertFalse(TermuxCommandPolicy.isReadOnly("apt install python"))
        assertFalse(TermuxCommandPolicy.isReadOnly("rm -rf /"))
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
    }

    @Test fun toolAccessMetadataIsAvailableToTheScopeGuard() {
        assertEquals(ToolAccess.READ, ToolRegistry.access("discussion_search"))
        assertEquals("orion_core", AgentPolicy.packForTool("discussion_search"))
        assertTrue(OrionMemoryCapabilityExecutor.supports("discussion_search"))
        assertEquals(ToolAccess.READ, ToolRegistry.access("read_screen"))
        assertEquals("phone_control", AgentPolicy.packForTool("look"))
        assertEquals(ToolAccess.READ, ToolRegistry.access("verify_send"))
        assertEquals(ToolAccess.WRITE, ToolRegistry.access("type"))
        assertEquals(ToolAccess.WRITE, ToolRegistry.access("telegram_send"))
        assertEquals(ToolAccess.READ, ToolRegistry.access("termux_command"))
        assertTrue(ToolRegistry.find("termux_command")!!.promptLine.contains("read-only diagnostic"))
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
    @Test fun everyRequestReceivesFullContractsForAllRegisteredTools() {
        val prompt = ToolRegistry.promptSection("open whatsapp and read my last chat")
        ToolRegistry.all.forEach { tool ->
            assertTrue("Missing full tool contract for ${tool.name}", prompt.contains("- " + tool.promptLine))
        }
        assertTrue(prompt.contains("- bybit_public_ticker {symbol}: read the public Bybit"))
        assertTrue(prompt.contains("- read_screen {}"))
    }

    @Test fun requestTextDoesNotHideCapabilitiesFromThePlanner() {
        assertEquals(ToolRegistry.promptSection(), ToolRegistry.promptSection("what is the bitcoin price on bybit"))
        assertEquals(ToolRegistry.promptSection(), ToolRegistry.promptSection(null))
    }
}
