package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionEvidenceSanitizerTest {
    @Test fun browserEvidenceRejectsUnsafeUrlsAndRedactsSecrets() {
        val safe = OrionEvidenceSanitizer.evidence(
            OrionActivityEvidence.Browser("Research", "javascript:alert(1)", "api_key=abc123 Bearer abc.def")
        ) as OrionActivityEvidence.Browser
        assertEquals("", safe.url)
        assertTrue(safe.excerpt!!.contains("[redacted]"))
        assertTrue(!safe.excerpt!!.contains("abc123"))
    }

    @Test fun browserEvidenceAllowsHttpAndHttpsWithoutCredentials() {
        val https = OrionEvidenceSanitizer.evidence(OrionActivityEvidence.Browser("Docs", "https://example.com/a"))
            as OrionActivityEvidence.Browser
        val credentials = OrionEvidenceSanitizer.evidence(OrionActivityEvidence.Browser("Bad", "https://user:pass@example.com"))
            as OrionActivityEvidence.Browser
        assertEquals("https://example.com/a", https.url)
        assertEquals("", credentials.url)
    }

    @Test fun terminalAndFileEvidenceAreBoundedAndNonNegative() {
        val terminal = OrionEvidenceSanitizer.evidence(
            OrionActivityEvidence.Terminal("s".repeat(200), "run tests", "x".repeat(1000), -1)
        ) as OrionActivityEvidence.Terminal
        val file = OrionEvidenceSanitizer.evidence(
            OrionActivityEvidence.File("/tmp/result", -10, "write")
        ) as OrionActivityEvidence.File
        assertEquals(96, terminal.sessionId.length)
        assertEquals(400, terminal.outputTail.length)
        assertEquals(0L, file.byteCount)
    }

    @Test fun typedEvidenceJsonRoundTripSanitizesEveryField() {
        val original = OrionActivityEvidence.Terminal(
            "session-1", "run tests", "Bearer abcdefghijklmnop", 0
        )
        val restored = OrionActivityEvidence.fromJson(original.toJson().toString()) as OrionActivityEvidence.Terminal
        assertEquals("session-1", restored.sessionId)
        assertEquals("run tests", restored.commandLabel)
        assertTrue(restored.outputTail.contains("[redacted]"))
        assertTrue(!restored.outputTail.contains("abcdefghijklmnop"))
        assertEquals(0, restored.exitCode ?: -1)
    }

    @Test fun diffAndHandoffEvidenceAreBounded() {
        val diff = OrionEvidenceSanitizer.evidence(
            OrionActivityEvidence.CodeDiff("f".repeat(300), -2, 3, "summary")
        ) as OrionActivityEvidence.CodeDiff
        val handoff = OrionEvidenceSanitizer.evidence(
            OrionActivityEvidence.AgentHandoff("a".repeat(120), "parent", "objective", "running")
        ) as OrionActivityEvidence.AgentHandoff
        assertEquals(160, diff.fileLabel.length)
        assertEquals(0, diff.additions)
        assertEquals(96, handoff.agentId.length)
    }
}
