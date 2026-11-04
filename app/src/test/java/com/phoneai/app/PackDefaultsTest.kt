package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class PackDefaultsTest {
    @Test fun theToolsOrionNeedsToHelpItselfAreOnFromFirstLaunch() {
        listOf(
            "connect_service", "service_status", "capability_note", "standing_order", "vault_request", "vault_get",
            "shell", "github_account_read", "gmail_list_messages",
            "tap", "read_screen", "open_app"
        ).forEach { assertTrue(it, AgentPolicy.defaultOn(AgentPolicy.packForTool(it)!!)) }
    }

    @Test fun riskyPacksStayOptIn() {
        listOf("termux_bridge", "remote_computer", "messaging", "proactive", "self_evolution")
            .forEach { assertFalse(it, AgentPolicy.defaultOn(it)) }
    }

    @Test fun phoneControlIsDefaultOnBecauseEveryActionIsStillGated() {
        // Operating the device is Orion's purpose; GatePolicy still decides every single
        // action and consequential tools still ask, so phone_control no longer forces the
        // user to find Agent control > Packs before Orion can do anything on-device.
        assertTrue(AgentPolicy.defaultOn("phone_control"))
    }

    @Test fun everyDefaultOnPackExists() {
        assertTrue(AgentPolicy.defaultOnPacks.all { id -> AgentPolicy.packs.any { it.id == id && it.implemented } })
    }
}
