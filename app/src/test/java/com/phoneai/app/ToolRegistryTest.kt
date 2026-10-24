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
            "mcp_call", "remember", "task_delete", "ask_user"
        )))
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
