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
        // Routing only chooses the shared executor. The normal owner policy still decides whether
        // a selected tool is allowed, needs Android access, or requires action-specific approval.
        assertTrue(OrionVoiceExecutionPolicy.shouldRouteToCommand("Send this message to Amma", emptyList(), ""))
        assertNotNull(AgentHooks.scopeBlockReason(
            "Send this message to Amma",
            "telegram_send",
            org.json.JSONObject().put("message", "Hello")
        ))
    }
}
