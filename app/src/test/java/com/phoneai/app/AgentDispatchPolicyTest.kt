package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDispatchPolicyTest {
    private fun block(
        request: String,
        tool: String,
        args: JSONObject = JSONObject(),
        targetLabel: String = "",
        currentPackage: String? = "com.example.chat",
        currentLabel: String? = "Chat",
        stopped: Boolean = false
    ): String? = AgentDispatchPolicy.blockReason(
        request, tool, args, targetLabel, currentPackage, currentLabel, stopped
    )

    @Test fun cancellationWinsEvenForOtherwiseAllowedActions() {
        val reason = block(
            request = "Open WhatsApp",
            tool = "open_app",
            args = JSONObject().put("name", "WhatsApp"),
            stopped = true
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("stopped", ignoreCase = true))
    }

    @Test fun cancellationCanBeRecheckedImmediatelyBeforeSideEffects() {
        assertNotNull(AgentDispatchPolicy.cancellationBlockReason("click_text", stopped = true))
        assertNull(AgentDispatchPolicy.cancellationBlockReason("click_text", stopped = false))
    }

    @Test fun unknownToolsAndChatModeAreDeniedAtDispatch() {
        assertNotNull(block("Explain Bluetooth", "read_screen"))
        assertNotNull(block("Open WhatsApp", "run_shell"))
    }

    @Test fun researchAndReadOnlyRequestsCannotMutatePhoneState() {
        assertNotNull(block("Research Android accessibility online", "open_app", JSONObject().put("name", "Chrome")))
        assertNotNull(block("Read-only: inspect notifications without changing anything", "type", JSONObject().put("text", "hello")))
    }

    @Test fun explicitSendCanProceedButDraftOnlyCannotPressSend() {
        assertNull(block("Write and send this message to Amma", "click_text", JSONObject().put("text", "Send")))
        assertNotNull(block("Draft a message to Amma without sending", "click_text", JSONObject().put("text", "Send")))
        assertNotNull(block("Do not send this message to Amma", "click_text", JSONObject().put("text", "Send")))
    }

    @Test fun credentialTypingIsBlockedButOrdinaryGameAndChatWordsPass() {
        assertNotNull(block("Type this text in chat", "type", JSONObject().put("text", "enter your PIN")))
        assertNull(block(
            "Type a note in FC Mobile chat",
            "type",
            JSONObject().put("text", "transfer the card to my wallet and pin this message"),
            currentPackage = "com.example.game",
            currentLabel = "FC Mobile"
        ))
    }

    @Test fun readOnlyInspectionIsAllowedButActionInsideBankingAppIsBlocked() {
        assertNull(block(
            "Open the banking app and inspect the screen",
            "read_screen",
            currentPackage = "com.example.bankapp",
            currentLabel = "Banking"
        ))
        assertNotNull(block(
            "Open the banking app and click Continue",
            "click_text",
            JSONObject().put("text", "Continue"),
            currentPackage = "com.example.bankapp",
            currentLabel = "Banking"
        ))
    }

    @Test fun sensitiveEntryIsBlockedEvenWhenTheTaskScopeAllowsPhoneAutomation() {
        val reason = block(
            "Type this into the login form",
            "browser_type",
            JSONObject().put("target", "email").put("text", "4111 1111 1111 1111"),
            currentPackage = "com.example.browser",
            currentLabel = "Browser"
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("safety gate", ignoreCase = true))
    }
}
