package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sensitive financial and credential operations remain owner-controlled. */
class SafetyGateTest {
    @Test fun credentialEntryIsBlockedButOrdinaryTextIsAllowed() {
        listOf("enter your PIN", "card number", "my password is hunter2", "4111 1111 1111 1111").forEach { text ->
            assertNotNull(text, SafetyGate.blockReason("type", null, text, "com.example.chat", "Chat"))
        }
        assertNull(SafetyGate.blockReason("type", null, "Hello Amma", "com.whatsapp", "WhatsApp"))
    }

    @Test fun sensitiveBrowserFieldsAndFinancialAppsAreBlockedForWrites() {
        assertNotNull(SafetyGate.blockReason("browser_type", "password input", "hello", "com.example.browser", "Browser"))
        assertNotNull(SafetyGate.blockReason("click", null, null, "com.example.bankapp", "Banking"))
        assertNotNull(SafetyGate.blockReason("open_app", "Banking", null, "com.example.chat", "Chat"))
        assertNull(SafetyGate.blockReason("read_screen", null, null, "com.example.bankapp", "Banking"))
    }

    @Test fun sensitiveScreensAreDetectedWithMessagingSpecificRules() {
        assertTrue(SafetyGate.isSensitiveActionScreen("Confirm payment to continue"))
        assertTrue(SafetyGate.isSensitiveActionScreen("Enter your PIN", "com.whatsapp"))
        assertFalse(SafetyGate.isSensitiveActionScreen("Place order"))
    }

    @Test fun credentialDetectionStillKeepsSecretsOutOfMemory() {
        assertTrue(SafetyGate.containsSensitiveCredentialText("my password is hunter2"))
        assertTrue(SafetyGate.isSensitiveApp("com.example.bankapp", "Banking"))
        assertFalse(SafetyGate.containsSensitiveCredentialText("I pinned the chat and collected a card."))
    }
}
