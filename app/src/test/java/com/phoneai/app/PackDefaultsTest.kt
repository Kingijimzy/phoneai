package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class PackDefaultsTest {
    @Test fun theToolsOrionNeedsToHelpItselfAreOnFromFirstLaunch() {
        listOf("connect_service", "service_status", "capability_note", "standing_order", "vault_request", "vault_get", "shell", "github_account_read", "gmail_list_messages", "open_app", "click", "read_screen")
            .forEach { assertTrue(it, AgentPolicy.defaultOn(AgentPolicy.packForTool(it)!!)) }
    }

    @Test fun consequentialAndExternalPacksStayOptIn() {
        listOf("termux_bridge", "remote_computer", "messaging", "proactive", "self_evolution")
            .forEach { assertFalse(it, AgentPolicy.defaultOn(it)) }
        // Phone control is available by default so Orion can carry out ordinary explicitly
        // requested tasks; consequential actions still pass through action-level approval.
        assertTrue(AgentPolicy.defaultOn("phone_control"))
    }

    @Test fun everyDefaultOnPackExists() {
        assertTrue(AgentPolicy.defaultOnPacks.all { id -> AgentPolicy.packs.any { it.id == id && it.implemented } })
    }
}
