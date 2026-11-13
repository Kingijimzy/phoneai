package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionNotificationContentPolicyTest {
    @Test fun excludesFinanceAndAuthenticatorPackages() {
        assertFalse(OrionNotificationContentPolicy.isSafe("com.example.bankapp", "Bank App", "Hello", "Welcome"))
        assertFalse(OrionNotificationContentPolicy.isSafe("com.example.authenticator", "Authenticator", "Code", "123456"))
    }
    @Test fun excludesCredentialAndPaymentCodes() {
        assertFalse(OrionNotificationContentPolicy.isSafe("com.example.chat", "Chat", "Verification code", "Your login code is 123456"))
        assertFalse(OrionNotificationContentPolicy.isSafe("com.example.chat", "Chat", "Transaction alert", "Card payment received"))
    }
    @Test fun retainsOrdinaryMessagingNotifications() {
        assertTrue(OrionNotificationContentPolicy.isSafe("com.whatsapp", "WhatsApp", "Alex", "Are we meeting today?"))
    }
}