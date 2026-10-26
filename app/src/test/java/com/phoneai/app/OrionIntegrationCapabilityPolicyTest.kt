package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionIntegrationCapabilityPolicyTest {
    @Test fun publicGithubLookupDoesNotImplyPrivateAccountRead() {
        val caps = OrionIntegrationCapabilityPolicy.capabilities(setOf("github_public"), telegramConfigured = false)
        assertFalse(caps.contains("github.account.read"))
    }

    @Test fun genericEmailAndDriveMentionsDoNotImplyPrivateConnectorTools() {
        val caps = OrionIntegrationCapabilityPolicy.capabilities(
            setOf("draft_email", "open_drive_document", "integration_status"),
            telegramConfigured = false
        )
        assertFalse(caps.contains("gmail.messages.read"))
        assertFalse(caps.contains("google_drive.files.read"))
    }

    @Test fun explicitProviderToolsAreMappedPrecisely() {
        val caps = OrionIntegrationCapabilityPolicy.capabilities(
            setOf("github_private_repo_read", "gmail_list_messages", "google_drive_list_files"),
            telegramConfigured = false
        )
        assertTrue(caps.contains("github.account.read"))
        assertTrue(caps.contains("gmail.messages.read"))
        assertTrue(caps.contains("google_drive.files.read"))
    }

    @Test fun telegramRequiresBothWorkingToolsAndAConfiguredBot() {
        assertFalse(OrionIntegrationCapabilityPolicy.capabilities(
            setOf("telegram_inbox", "telegram_send"), telegramConfigured = false
        ).contains("telegram.messages"))
        assertTrue(OrionIntegrationCapabilityPolicy.capabilities(
            setOf("telegram_inbox", "telegram_send"), telegramConfigured = true
        ).contains("telegram.messages"))
        assertFalse(OrionIntegrationCapabilityPolicy.capabilities(
            setOf("telegram_status", "telegram_inbox"), telegramConfigured = true
        ).contains("telegram.messages"))
    }

    @Test fun genericShellDoesNotClaimTermuxConnectorCapability() {
        assertFalse(OrionIntegrationCapabilityPolicy.capabilities(
            setOf("shell", "termux_command"), telegramConfigured = false
        ).contains("shell.execute"))
        assertEquals(setOf("shell.execute"), OrionIntegrationCapabilityPolicy.capabilities(
            setOf("termux_shell", "termux_command"), telegramConfigured = false
        ))
    }
}
