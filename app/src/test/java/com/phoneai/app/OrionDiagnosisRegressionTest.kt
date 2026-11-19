package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Guards for the exact defects found when diagnosing why Orion felt like a limited bot. */
class OrionDiagnosisRegressionTest {
    private fun src(name: String) = File("src/main/java/com/phoneai/app/$name").readText()

    @Test fun voiceNeverTellsTheModelItCannotActWhileToolsAreReachable() {
        val agent = src("Agent.kt")
        assertFalse("live voice must not be told it cannot execute actions", agent.contains("cannot execute it yet"))
        assertFalse(agent.contains("The live-call path cannot execute actions"))
        assertTrue(agent.contains("toolsAvailable"))
    }

    @Test fun handoffResultIsThisRunsReplyNotTheLastAiLine() {
        val svc = src("PhoneAiService.kt")
        assertTrue(svc.contains("aiLinesBefore"))
        assertFalse(svc.contains("val result = transcript.lastOrNull { it.first == \"ai\" }"))
    }

    @Test fun handoffCarriesEnoughDetailToSpeakAFullAnswer() {
        assertTrue(GeminiLivePolicy.MAX_TOOL_RESULT_CHARS >= 1500)
        val mid = "word ".repeat(300)
        assertEquals(mid.trim(), GeminiLivePolicy.clipToolResult(mid))
        assertTrue(GeminiLivePolicy.clipToolResult("word ".repeat(1000)).endsWith("(full details are in the chat)"))
    }

    @Test fun personaStatesOwnershipAndHasNoEscapeSequenceLeaks() {
        assertTrue(OrionPersona.CORE.contains("OWNERSHIP AND CONFIDENCE"))
        assertFalse(OrionPersona.CORE.contains("\\n"))
        assertFalse(OrionPersona.CORE.contains("\\\""))
    }
}
