package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class PackDefaultsTest {
    @Test fun theToolsOrionNeedsToHelpItselfAreOnFromFirstLaunch() {
        listOf("connect_service", "service_status", "capability_note", "standing_order", "vault_request", "vault_get", "shell", "github_account_read", "gmail_list_messages")
            .forEach { assertTrue(it, AgentPolicy.defaultOn(AgentPolicy.packForTool(it)!!)) }
    }

    @Test fun corePhoneAndMessagingToolsAreDiscoverableButOptionalBridgesStayOptIn() {
        // Core phone and messaging tools are visible from first launch. This does not grant
        // Android permissions or bypass action confirmations; those remain enforced at dispatch.
        listOf("phone_control", "messaging").forEach {
            assertTrue("$it should be enabled by default", AgentPolicy.defaultOn(it))
        }
        listOf("termux_bridge", "remote_computer", "proactive", "self_evolution").forEach {
            assertFalse("$it must remain opt-in", AgentPolicy.defaultOn(it))
        }
    }

    @Test fun everyDefaultOnPackExists() {
        assertTrue(AgentPolicy.defaultOnPacks.all { id -> AgentPolicy.packs.any { it.id == id && it.implemented } })
    }
}
