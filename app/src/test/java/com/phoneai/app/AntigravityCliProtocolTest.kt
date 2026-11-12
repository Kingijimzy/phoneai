package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class AntigravityCliProtocolTest {
    @Test fun promptInvocationUsesOfficialHeadlessJsonModeAndFullPermissionFlag() {
        val call = AntigravityCliProtocol.headlessPrompt("summarize this project", "gemini-4-carbon-high")
        assertEquals(
            listOf("agy", "-p", "summarize this project", "--output-format", "stream-json",
                "--dangerously-skip-permissions", "--model", "gemini-4-carbon-high"),
            call.argv
        )
        assertTrue(call.requiresExistingLogin)
    }

    @Test fun blankPromptIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AntigravityCliProtocol.headlessPrompt(" ")
        }
    }

    @Test fun parsesDocumentedStepUpdateTextDelta() {
        val event = AntigravityCliProtocol.parseStreamEvent(
            """{"event":"step_update","step_update":{"step_type":"agent_response","text_delta":"hello"}}"""
        )!!
        assertEquals("step_update", event.type)
        assertEquals("hello", event.text)
    }

    @Test fun parsesFinalResultAndStatus() {
        val event = AntigravityCliProtocol.parseStreamEvent(
            """{"event":"result","result":{"status":"SUCCESS","response":"verified"}}"""
        )!!
        assertEquals("result", event.type)
        assertEquals("SUCCESS", event.status)
        assertEquals("verified", event.text)
    }

    @Test fun parsesModelSlugsFromOfficialCatalogue() {
        val models = AntigravityCliProtocol.parseModelList(
            "gemini-4-carbon-high      Gemini 4 Carbon (High)\ngemini-3.8-flash-medium   Gemini 3.8 Flash (Medium)"
        )
        assertEquals(listOf("gemini-4-carbon-high", "gemini-3.8-flash-medium"), models)
    }

    @Test fun malformedTerminalOutputIsNotAnAiResponse() {
        assertNull(AntigravityCliProtocol.parseStreamEvent("Sign in required"))
    }

    @Test fun authenticationFailureIsNotReportedAsConnected() {
        val event = AntigravityCliProtocol.Event("error", error = "Authentication required")
        assertTrue(AntigravityCliProtocol.isAuthenticationRequired(event))
    }
}
