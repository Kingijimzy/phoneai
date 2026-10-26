package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionIntegrationCapabilityPolicyTest {
    @Test fun publicGithubLookupDoesNotImplyPrivateAccountRead() {
        val caps = OrionIntegrationCapabilityPolicy.capabilities(setOf("github_public"), termuxReady = false)
        assertFalse(caps.contains("github.account.read"))
    }

    @Test fun genericEmailAndDriveMentionsDoNotImplyPrivateConnectorTools() {
        val caps = OrionIntegrationCapabilityPolicy.capabilities(
            setOf("draft_email", "open_drive_document", "integration_status"),
            termuxReady = false
        )
        assertFalse(caps.contains("gmail.messages.read"))
        assertFalse(caps.contains("google_drive.files.read"))
    }

    @Test fun explicitProviderToolsAreMappedPrecisely() {
        val caps = OrionIntegrationCapabilityPolicy.capabilities(
            setOf("github_private_repo_read", "gmail_list_messages", "google_drive_list_files"),
            termuxReady = false
        )
        assertTrue(caps.contains("github.account.read"))
        assertTrue(caps.contains("gmail.messages.read"))
        assertTrue(caps.contains("google_drive.files.read"))
    }

    @Test fun telegramNeedsBothEnabledToolEntrypoints() {
        assertFalse(OrionIntegrationCapabilityPolicy.capabilities(
            setOf("telegram_inbox"), termuxReady = false
        ).contains("telegram.messages"))
        assertTrue(OrionIntegrationCapabilityPolicy.capabilities(
            setOf("telegram_inbox", "telegram_send"), termuxReady = false
        ).contains("telegram.messages"))
    }

    @Test fun termuxNeedsBothToolEntrypointsAndReadyBridge() {
        assertFalse(OrionIntegrationCapabilityPolicy.capabilities(
            setOf("shell", "termux_command"), termuxReady = false
        ).contains("shell.execute"))
        assertEquals(setOf("shell.execute"), OrionIntegrationCapabilityPolicy.capabilities(
            setOf("shell", "termux_command"), termuxReady = true
        ))
        assertFalse(OrionIntegrationCapabilityPolicy.capabilities(
            setOf("shell", "termux_command", "github_public"), termuxReady = false
        ).contains("shell.execute"))
    }

    @Test fun unconnectedTelegramPlansConnectionInsteadOfResearchingMissingCode() {
        val plan = OrionIntegrationPlanner.plan(
            OrionIntegrationPlanner.Provider.TELEGRAM,
            OrionIntegrationPlanner.State.AVAILABLE_NOT_CONNECTED,
            "connect my Telegram bot",
            setOf("telegram.messages")
        )
        assertEquals(OrionIntegrationPlanner.NextStep.ASK_TO_CONNECT, plan.nextStep)
    }
}
