package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class AntigravityCliProtocolTest {
    @Test fun promptInvocationUsesHeadlessJsonModeWithoutBypassingProviderApprovals() {
        val call = AntigravityCliProtocol.headlessPrompt("summarize this project", "gemini-4-carbon-high")
        assertEquals(
            listOf("agy", "-p", "summarize this project", "--output-format", "stream-json",
                "--model", "gemini-4-carbon-high"),
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

    @Test fun parsesTerminalResultAndRejectsUnrecognizedLines() {
        val result = AntigravityCliProtocol.parseStreamEvent(
            """{"event":"result","result":{"status":"SUCCESS","response":"Done."}}"""
        )
        assertEquals("result", result?.type)
        assertEquals("SUCCESS", result?.status)
        assertEquals("Done.", result?.text)
        assertNull(AntigravityCliProtocol.parseStreamEvent("not-json"))
    }
    @Test fun compactPromptKeepsNewestContextWithinConfiguredLimit() {
        val original = "older-context " + "x".repeat(50_000) + " NEWEST-USER-REQUEST"
        val compact = AntigravityCliProtocol.compactPrompt(original, 2_000)
        assertTrue(compact.length <= 2_000)
        assertTrue(compact.contains("Earlier conversation context omitted"))
        assertTrue(compact.endsWith("NEWEST-USER-REQUEST"))
    }

    @Test fun contextOverflowDetectorRecognizesCompactionFailure() {
        assertTrue(AntigravityCliProtocol.isContextOverflow(
            "The conversation is still too large after context compaction (214052 characters)"
        ))
        assertTrue(!AntigravityCliProtocol.isContextOverflow("Google account authentication required"))
    }

    @Test fun promptInvocationRejectsOversizedContextAfterCompaction() {
        val compact = AntigravityCliProtocol.compactPrompt("x".repeat(100_000))
        assertTrue(compact.length <= AntigravityCliProtocol.MAX_PROMPT_CHARS)
        assertThrows(IllegalArgumentException::class.java) {
            AntigravityCliProtocol.headlessPrompt("x".repeat(AntigravityCliProtocol.MAX_PROMPT_CHARS + 1))
        }
    }

}
