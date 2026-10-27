package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionIntegrationCapabilityPolicyTest {
    @Test fun publicGithubLookupDoesNotImplyPrivateAccountRead() {
        val caps = OrionIntegrationCapabilityPolicy.capabilities(setOf("github_public"))
        assertFalse(caps.contains("github.account.read"))
    }

    @Test fun genericEmailAndDriveMentionsDoNotImplyPrivateConnectorTools() {
        val caps = OrionIntegrationCapabilityPolicy.capabilities(
            setOf("draft_email", "open_drive_document", "integration_status"),
            
        )
        assertFalse(caps.contains("gmail.messages.read"))
        assertFalse(caps.contains("google_drive.files.read"))
    }

    @Test fun explicitProviderToolsAreMappedPrecisely() {
        val caps = OrionIntegrationCapabilityPolicy.capabilities(
            setOf("github_private_repo_read", "gmail_list_messages", "google_drive_list_files"),
            
        )
        assertTrue(caps.contains("github.account.read"))
        assertTrue(caps.contains("gmail.messages.read"))
        assertTrue(caps.contains("google_drive.files.read"))
    }

    @Test fun telegramNeedsBothEnabledToolEntrypoints() {
        assertFalse(OrionIntegrationCapabilityPolicy.capabilities(
            setOf("telegram_inbox")
        ).contains("telegram.messages"))
        assertTrue(OrionIntegrationCapabilityPolicy.capabilities(
            setOf("telegram_inbox", "telegram_send")
        ).contains("telegram.messages"))
    }

    @Test fun builtInShellDoesNotDependOnTermux() {
        assertEquals(setOf("shell.execute"), OrionIntegrationCapabilityPolicy.capabilities(
            setOf("shell")
        ))
        assertTrue(OrionIntegrationCapabilityPolicy.capabilities(
            setOf("shell", "github_public")
        ).contains("shell.execute"))
    }

    @Test fun unsupportedTermuxToolNeverCreatesASeparateIntegrationCapability() {
        assertFalse(OrionIntegrationCapabilityPolicy.capabilities(setOf("termux_command")).contains("termux.execute"))
        assertEquals(setOf("shell.execute"), OrionIntegrationCapabilityPolicy.capabilities(setOf("shell", "termux_command")))
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

    @Test fun disabledConnectorToolsAreReportedAsMissingEvenIfAccountIsConfigured() {
        val plan = OrionIntegrationPlanner.plan(
            OrionIntegrationPlanner.Provider.TELEGRAM,
            OrionIntegrationPlanner.State.CAPABILITY_MISSING,
            "read my Telegram inbox",
            emptySet()
        )
        assertEquals(OrionIntegrationPlanner.NextStep.ASK_TO_ENABLE_OR_INSTALL, plan.nextStep)
    }

    @Test fun legacyTermuxWordingResolvesToBuiltInShellWithoutInventingABridge() {
        assertEquals(OrionIntegrationPlanner.Provider.BUILTIN_SHELL,
            OrionIntegrationPlanner.providerFor("check my Termux shell"))
        assertFalse(OrionIntegrationCapabilityPolicy.capabilities(setOf("termux_command")).contains("termux.execute"))
    }
}
