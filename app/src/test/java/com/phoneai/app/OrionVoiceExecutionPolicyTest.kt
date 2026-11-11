package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrionVoiceExecutionPolicyTest {
    @Test fun voiceAndTypedChatShareTheStructuredExecuteDecision() {
        assertEquals(
            OrionTurnDecision.Execute("Open WhatsApp and inspect the latest conversation"),
            OrionTurnDecisionParser.parse("""{"decision":"execute","objective":"Open WhatsApp and inspect the latest conversation"}""")
        )
    }

    @Test fun normalSpokenConversationRemainsAReply() {
        assertEquals(
            OrionTurnDecision.Reply("A good conversation requires listening and curiosity."),
            OrionTurnDecisionParser.parse("""{"decision":"reply","text":"A good conversation requires listening and curiosity."}""")
        )
    }

    @Test fun ambiguousSpokenTargetAsksForClarificationInsteadOfGuessing() {
        assertEquals(
            OrionTurnDecision.Clarify("Which contact do you mean?"),
            OrionTurnDecisionParser.parse("""{"decision":"clarify","question":"Which contact do you mean?"}""")
        )
    }

    @Test fun routeDecisionNeverGrantsPermissionAndDispatchPolicyRemainsAuthoritative() {
        assertFalse(AgentHooks.requiresConfirmation("telegram_send", JSONObject().put("message", "Hello")))
        assertTrue(AgentHooks.requiresConfirmation("workspace_delete", JSONObject()))
        assertTrue(AgentHooks.requiresConfirmation("mcp_call", JSONObject().put("name", "delete_event")))
        assertFalse(AgentHooks.requiresConfirmation("computer_settings", JSONObject().put("action", "restart")))
        assertFalse(AgentHooks.requiresConfirmation("computer_settings", JSONObject().put("action", "shutdown")))
        assertFalse(AgentHooks.requiresConfirmation("computer_settings", JSONObject().put("action", "wifi_toggle")))
    }
}
