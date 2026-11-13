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
        // Voice routes through the same dispatcher and inherits exact-operation owner approval.
        assertTrue(OrionVoiceExecutionPolicy.shouldRouteToCommand("Send this message to Amma", emptyList(), ""))
        assertEquals(ToolAccess.WRITE, ToolRegistry.access("telegram_send"))
        assertEquals("messaging", AgentPolicy.packForTool("telegram_send"))
        assertTrue(AgentHooks.requiresConfirmation("telegram_send", org.json.JSONObject().put("message", "Hello")))
        assertTrue(AgentHooks.requiresConfirmation("computer_settings", org.json.JSONObject().put("action", "restart")))
        assertTrue(AgentHooks.requiresConfirmation("computer_settings", org.json.JSONObject().put("action", "shutdown")))
        assertTrue(AgentHooks.requiresConfirmation("computer_settings", org.json.JSONObject().put("action", "wifi_toggle")))
        assertTrue(AgentHooks.requiresConfirmation("task_delete", org.json.JSONObject().put("id", "task-1")))
    }
    @Test fun progressCueOnlyTriggersForPotentiallySlowRegisteredToolNames() {
        assertTrue(OrionProgressCuePolicy.shouldAnnounce(listOf("python_exec")))
        assertTrue(OrionProgressCuePolicy.shouldAnnounce(listOf("read_screen")))
        assertTrue(OrionProgressCuePolicy.shouldAnnounce(listOf("browser_open")))
        assertFalse(OrionProgressCuePolicy.shouldAnnounce(listOf("click_text")))
        assertFalse(OrionProgressCuePolicy.shouldAnnounce(listOf("routine_unknown")))
        assertFalse(OrionProgressCuePolicy.shouldAnnounce(emptyList()))
    }

}
