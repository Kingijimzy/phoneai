package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGateTest {
    @Test fun ordinaryGameAndChatScreensAreNotSensitive() {
        val game = "FC Mobile · transfer market · purchase players · trading tokens · wallet · card collection · pin your favorite team"
        val chat = "Can you transfer the file? I bought a card and pinned the message in our group chat."
        assertFalse(SafetyGate.isSensitiveActionScreen(game))
        assertFalse(SafetyGate.isSensitiveActionScreen(chat))
        assertNull(SafetyGate.blockReason("type", null, "transfer the file to my friend", "com.example.chat", "Chat"))
        assertNull(SafetyGate.blockReason("type", null, "I purchased FC points yesterday", "com.example.game", "FC Mobile"))
    }

    @Test fun genericFinancialVocabularyDoesNotBlockTyping() {
        listOf("transfer", "purchase", "trading", "wallet", "card", "pin").forEach { word ->
            assertNull("Unexpected block for $word", SafetyGate.blockReason("type", null, "The word $word appears in ordinary conversation.", "com.example.chat", "Chat"))
        }
        assertFalse(SafetyGate.containsSensitiveCredentialText("I pinned the chat and collected a card."))
    }

    @Test fun explicitCredentialContextsAreBlocked() {
        listOf("enter your PIN", "card number", "CVV", "password", "one-time code", "bank account number", "recovery phrase", "private key").forEach { text ->
            assertNotNull("Expected credential block for: $text", SafetyGate.blockReason("type", null, text, "com.example.chat", "Chat"))
        }
        assertNotNull(SafetyGate.blockReason("browser_type", "password input", "hello", "com.example.browser", "Browser"))
        assertNotNull(SafetyGate.blockReason("browser_type", "card number", "hello", "com.example.browser", "Browser"))
    }

    @Test fun actionScreenRequiresConsequentialContext() {
        assertTrue(SafetyGate.isSensitiveActionScreen("Confirm payment to continue"))
        assertTrue(SafetyGate.isSensitiveActionScreen("Enter your PIN"))
        assertTrue(SafetyGate.isSensitiveActionScreen("Place order"))
        assertFalse(SafetyGate.isSensitiveActionScreen("Trading tips: how to buy a new player card"))
        assertFalse(SafetyGate.isSensitiveActionScreen("Wallet inventory and card collection"))
        assertFalse(SafetyGate.isSensitiveActionScreen("Transfer market opens at 6 PM"))
    }

    @Test fun sensitiveAppsAndLongCardNumbersRemainProtected() {
        assertTrue(SafetyGate.isSensitiveApp("com.example.bankapp", "Banking"))
        assertNotNull(SafetyGate.blockReason("type", null, "4111 1111 1111 1111", "com.example.chat", "Chat"))
        assertNull(SafetyGate.blockReason("read_screen", null, null, "com.example.bankapp", "Banking"))
        assertNotNull(SafetyGate.blockReason("click", null, null, "com.example.bankapp", "Banking"))
    }
}
