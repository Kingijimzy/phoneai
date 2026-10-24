package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPrivacyFilterTest {
    @Test
    fun removesEmailUrlAndPhoneFromRecoveryCommand() {
        val safe = AgentPrivacyFilter.safeCommand(
            "Send to jane@example.com at https://example.com/private and call +1 202-555-0199"
        )
        assertFalse(safe.contains("jane@example.com"))
        assertFalse(safe.contains("https://example.com/private"))
        assertFalse(safe.contains("202-555-0199"))
        assertTrue(safe.contains("[email redacted]"))
        assertTrue(safe.contains("[URL redacted]"))
        assertTrue(safe.contains("[number redacted]"))
    }

    @Test
    fun redactsCommonCredentialLabelsAndBearerTokens() {
        val safe = AgentPrivacyFilter.safeCommand(
            "api_key=abc123 password: secret123 Bearer eyJhbGciOiJIUzI1NiJ9"
        )
        assertFalse(safe.contains("abc123"))
        assertFalse(safe.contains("secret123"))
        assertFalse(safe.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(safe.contains("[redacted]"))
    }

    @Test
    fun preservesOrdinaryTaskMeaningAndBoundsLength() {
        val safe = AgentPrivacyFilter.safeCommand("Open Settings and check battery")
        assertTrue(safe.contains("Open Settings"))
        assertTrue(safe.contains("check battery"))
        assertTrue(AgentPrivacyFilter.safeCommand("x".repeat(3000)).length <= 2000)
    }

    @Test
    fun phoneRedactionDoesNotConsumeAdjacentLetters() {
        val safe = AgentPrivacyFilter.safeCommand("Order A12345678B should stay searchable")
        assertTrue(safe.contains("A12345678B"))
    }
}
