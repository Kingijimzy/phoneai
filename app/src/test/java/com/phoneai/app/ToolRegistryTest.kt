package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolRegistryTest {
    @Test fun registryNamesAreUniqueAndContainEveryRuntimeToolFamily() {
        val names = ToolRegistry.all.map { it.name }
        assertEquals("Each tool name must have exactly one definition.", names.size, names.toSet().size)
        assertTrue(names.containsAll(setOf(
            "read_screen", "verify_send", "type", "click_text", "browser_snapshot",
            "browser_type", "weather", "bybit_public_ticker", "telegram_send",
            "mcp_call", "remember", "task_delete", "ask_user", "tool_help", "trade_history_summary", "gaming_session_summary", "gaming_session_note_add", "gaming_focus_preset"
        )))
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
    }

    @Test fun toolAccessMetadataIsAvailableToTheScopeGuard() {
        assertEquals(ToolAccess.READ, ToolRegistry.access("read_screen"))
        assertEquals(ToolAccess.READ, ToolRegistry.access("verify_send"))
        assertEquals(ToolAccess.WRITE, ToolRegistry.access("type"))
        assertEquals(ToolAccess.WRITE, ToolRegistry.access("telegram_send"))
        assertNull("Removed Termux bridge must not be advertised as a runtime tool", ToolRegistry.access("termux_command"))
        assertNull("Unsupported Termux bridge must not map to a capability pack", AgentPolicy.packForTool("termux_command"))
        assertFalse(AgentPolicy.packs.any { it.id == "termux_bridge" })
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

class ToolRegistryPromptTrimTest {
    @Test fun plainDeviceRequestOmitsSpecialistToolDetailsButKeepsSignatures() {
        val trimmed = ToolRegistry.promptSection("open whatsapp and read my last chat")
        assertFalse(trimmed.contains("- bybit_public_ticker {symbol}: read the public Bybit"))
        assertTrue(trimmed.contains("bybit_public_ticker {symbol}"))
        assertTrue(trimmed.contains("- read_screen {}"))
        assertTrue(trimmed.length < ToolRegistry.promptSection().length)
    }

    @Test fun githubAndGmailRequestsIncludeFullConnectionToolContracts() {
        val github = ToolRegistry.promptSection("connect my GitHub account")
        assertTrue(github.contains("- github_account_read {path}: read-only GitHub GET"))
        assertTrue(github.contains("- connect_service {service}: open Orion's secure Capability board"))
        val gmail = ToolRegistry.promptSection("connect my Gmail account and read my inbox")
        assertTrue(gmail.contains("- gmail_list_messages {query?, limit?}: read-only Gmail inbox headers"))
        assertTrue(gmail.contains("- service_status {}: list each service"))
    }

    @Test fun capabilityDiscoveryReturnsCatalogueAndFullRelevantContracts() {
        val catalogue = ToolRegistry.describe("what can you do")
        assertTrue(catalogue.contains("REGISTERED CAPABILITY CATALOGUE"))
        assertTrue(catalogue.contains("browser_navigate"))
        assertTrue(catalogue.contains("tool_help"))
        assertFalse(catalogue.contains("termux_command"))

        val browser = ToolRegistry.describe("navigate browser to a website")
        assertTrue(browser.contains("browser_navigate {url}"))
        assertTrue(browser.contains("browser_snapshot {}"))
        assertTrue(browser.contains("access=write"))
    }

    @Test fun capabilityHelpIsAlwaysVisibleEvenWhenSpecialistDetailsAreTrimmed() {
        val prompt = ToolRegistry.promptSection("open whatsapp")
        assertTrue(prompt.contains("- tool_help {query}"))
        assertTrue(prompt.contains("CAPABILITY DISCOVERY"))
        assertTrue(prompt.contains("GOAL DECISION LOOP"))
    }

    @Test fun matchingRequestKeepsTheFullContract() {
        val trimmed = ToolRegistry.promptSection("what is the bitcoin price on bybit")
        assertTrue(trimmed.contains("- bybit_public_ticker {symbol}: read the public Bybit"))
        assertEquals(ToolRegistry.promptSection(), ToolRegistry.promptSection(null))
    }
}
