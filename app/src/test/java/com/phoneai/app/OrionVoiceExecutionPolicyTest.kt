package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionVoiceExecutionPolicyTest {
    @Test fun actionableVoiceRequestsUseTheSharedCommandPath() {
        assertTrue(OrionVoiceExecutionPolicy.shouldRouteToCommand("Open WhatsApp", emptyList(), ""))
        assertTrue(OrionVoiceExecutionPolicy.shouldRouteToCommand("Research the latest EURUSD news", emptyList(), ""))
        assertTrue(OrionVoiceExecutionPolicy.shouldRouteToCommand("Remember this decision: use the central task lifecycle", emptyList(), ""))
    }

    @Test fun ordinaryVoiceConversationStaysOnTheLowCostConversationPath() {
        assertFalse(OrionVoiceExecutionPolicy.shouldRouteToCommand("What makes a good conversation?", emptyList(), ""))
        assertFalse(OrionVoiceExecutionPolicy.shouldRouteToCommand("Tell me a short story", emptyList(), ""))
    }

    @Test fun contextualVoiceFollowUpsContinueTheExistingObjective() {
        val turns = listOf(
            "user" to "Investigate the PhoneAI integration and repair the central task lifecycle",
            "assistant" to "I found the integration gaps."
        )
        assertTrue(OrionVoiceExecutionPolicy.shouldRouteToCommand("Continue", turns, ""))
        assertTrue(OrionVoiceExecutionPolicy.shouldRouteToCommand("Fix that", turns, ""))
    }

    @Test fun voiceRoutingDoesNotGrantPermissionOrBypassTheDispatchPolicy() {
        // Voice uses the same structured model decision and guarded dispatcher as typed chat.
        // The route decision never grants permission; ordinary sends are not app-gated.
        assertTrue(OrionVoiceExecutionPolicy.shouldRouteToCommand("Send this message to Amma", emptyList(), ""))
        assertEquals(ToolAccess.WRITE, ToolRegistry.access("telegram_send"))
        assertEquals("messaging", AgentPolicy.packForTool("telegram_send"))
        assertFalse(AgentHooks.requiresConfirmation("telegram_send", org.json.JSONObject().put("message", "Hello")))
        assertTrue(AgentHooks.requiresConfirmation("workspace_delete", org.json.JSONObject()))
        assertTrue(AgentHooks.requiresConfirmation("mcp_call", org.json.JSONObject().put("name", "delete_event")))
        assertFalse(AgentHooks.requiresConfirmation("computer_settings", org.json.JSONObject().put("action", "restart")))
        assertFalse(AgentHooks.requiresConfirmation("computer_settings", org.json.JSONObject().put("action", "shutdown")))
        assertFalse(AgentHooks.requiresConfirmation("computer_settings", org.json.JSONObject().put("action", "wifi_toggle")))
    }
}
