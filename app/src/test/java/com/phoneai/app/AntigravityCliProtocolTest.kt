package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class AntigravityCliProtocolTest {
    @Test fun promptInvocationUsesOfficialHeadlessJsonMode() {
        val call = AntigravityCliProtocol.headlessPrompt("summarize this project")
        assertEquals(listOf("agy", "-p", "summarize this project", "--output-format", "stream-json"), call.argv)
        assertTrue(call.requiresExistingLogin)
    }

    @Test fun blankPromptIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AntigravityCliProtocol.headlessPrompt(" ")
        }
    }

    @Test fun parsesJsonlTextEvents() {
        val event = AntigravityCliProtocol.parseStreamEvent(
            """{"type":"message","content":"hello"}"""
        )!!
        assertEquals("message", event.type)
        assertEquals("hello", event.text)
    }

    @Test fun malformedTerminalOutputIsNotAnAiResponse() {
        assertNull(AntigravityCliProtocol.parseStreamEvent("Sign in required"))
    }

    @Test fun authenticationFailureIsNotReportedAsConnected() {
        val event = AntigravityCliProtocol.Event("error", error = "Authentication required")
        assertTrue(AntigravityCliProtocol.isAuthenticationRequired(event))
    }
}
