package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Free mode: the owner decides what Orion does. The safety gate no longer refuses banking apps, credential
 * fields or "sensitive" screens. The detection helpers remain only so credentials are not copied into
 * memory or logs.
 */
class SafetyGateTest {
    @Test fun nothingIsBlockedAtDispatch() {
        listOf("enter your PIN", "card number", "my password is hunter2", "4111 1111 1111 1111").forEach { text ->
            assertNull(text, SafetyGate.blockReason("type", null, text, "com.example.chat", "Chat"))
        }
        assertNull(SafetyGate.blockReason("browser_type", "password input", "hello", "com.example.browser", "Browser"))
        assertNull(SafetyGate.blockReason("click", null, null, "com.example.bankapp", "Banking"))
        assertNull(SafetyGate.blockReason("open_app", "Banking", null, "com.example.chat", "Chat"))
    }

    @Test fun screensAreNeverRefusedAsSensitive() {
        assertFalse(SafetyGate.isSensitiveActionScreen("Confirm payment to continue"))
        assertFalse(SafetyGate.isSensitiveActionScreen("Enter your PIN", "com.whatsapp"))
        assertFalse(SafetyGate.isSensitiveActionScreen("Place order"))
    }

    @Test fun credentialDetectionStillKeepsSecretsOutOfMemory() {
        assertTrue(SafetyGate.containsSensitiveCredentialText("my password is hunter2"))
        assertTrue(SafetyGate.isSensitiveApp("com.example.bankapp", "Banking"))
        assertFalse(SafetyGate.containsSensitiveCredentialText("I pinned the chat and collected a card."))
    }
}
