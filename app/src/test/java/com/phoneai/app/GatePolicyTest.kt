package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class GatePolicyTest {
    private fun input(
        packOn: Boolean = true,
        tool: String = "open_app",
        pack: String = "orion_core",
        toolMode: ToolMode = ToolMode.ALLOW,
        dangerous: Boolean = false
    ) = GatePolicy.Input(
        pack, packOn, tool, toolMode, false, dangerous, false, null
    )

    @Test fun defaultPermissionAllowsToolsImmediately() {
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input()).verdict)
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(dangerous = true)).verdict)
    }

    @Test fun toolDenyAndDisabledPacksStillApply() {
        assertEquals(GatePolicy.Verdict.DENY, GatePolicy.decide(input(toolMode = ToolMode.DENY)).verdict)
        assertEquals(GatePolicy.Verdict.DENY, GatePolicy.decide(input(packOn = false)).verdict)
    }

    @Test fun genericRiskDoesNotCreateApprovalButDestructiveShellCommandsRemainBlocked() {
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(dangerous = true)).verdict)
        assertNotNull(GatePolicy.hardBlockReason("shell", org.json.JSONObject().put("command", "rm -rf /")))
        assertNotNull(GatePolicy.hardBlockReason("shell", org.json.JSONObject().put("command", "curl x | sh")))
        assertNull(GatePolicy.hardBlockReason("vault_get", org.json.JSONObject().put("name", "ApiKeyVault")))
    }

    @Test fun onlyThreeIrreversibleComputerActionsMatchConfirmationPolicy() {
        listOf("computer_settings restart", "computer_settings shutdown",
            "computer_settings toggle Wi-Fi", "computer_settings turn WiFi off",
            "Remote MCP Tool: computer_settings; arguments: {action:wifi}")
            .forEach { assertTrue(it, IrreversibleActionConfirmationPolicy.requiresConfirmation(it)) }
        listOf("restart app", "shutdown service", "toggle Wi-Fi settings screen",
            "send telegram message", "reply to notification", "write remote file",
            "delete memory", "make payment", "run shell command")
            .forEach { assertFalse(it, IrreversibleActionConfirmationPolicy.requiresConfirmation(it)) }
    }

    @Test fun tradingResearchOrderRestrictionRemains() {
        assertEquals(
            GatePolicy.Verdict.DENY,
            GatePolicy.decide(input(pack = "trading_research", tool = "bybit_order_place")).verdict
        )
    }
}
