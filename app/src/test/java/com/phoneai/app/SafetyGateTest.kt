package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sensitive-content heuristics are informational and must never veto owner-directed tool execution. */
class SafetyGateTest {
    @Test fun credentialTextDoesNotBlockTyping() {
        listOf("enter your PIN", "card number", "my password is hunter2", "4111 1111 1111 1111").forEach { text ->
            assertNull(text, SafetyGate.blockReason("type", null, text, "com.example.chat", "Chat"))
        }
        assertNull(SafetyGate.blockReason("type", null, "Hello Amma", "com.whatsapp", "WhatsApp"))
    }

    @Test fun bankingAppsAndSensitiveBrowserFieldsDoNotBlockActions() {
        assertNull(SafetyGate.blockReason("browser_type", "password input", "hello", "com.example.browser", "Browser"))
        assertNull(SafetyGate.blockReason("click", null, null, "com.example.bankapp", "Banking"))
        assertNull(SafetyGate.blockReason("open_app", "Banking", null, "com.example.chat", "Chat"))
    }

    @Test fun sensitiveScreenClassificationIsNotAnExecutionGate() {
        assertFalse(SafetyGate.isSensitiveActionScreen("Confirm payment to continue"))
        assertFalse(SafetyGate.isSensitiveActionScreen("Enter your PIN", "com.whatsapp"))
        assertFalse(SafetyGate.isSensitiveActionScreen("Your playlist is ready"))
    }

    @Test fun credentialDetectionRemainsAvailableForNonBlockingDiagnostics() {
        assertTrue(SafetyGate.containsSensitiveCredentialText("my password is hunter2"))
        assertTrue(SafetyGate.isSensitiveApp("com.example.bankapp", "Banking"))
        assertFalse(SafetyGate.containsSensitiveCredentialText("I pinned the chat and collected a card."))
    }
}
