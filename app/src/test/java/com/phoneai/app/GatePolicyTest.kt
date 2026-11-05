package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class GatePolicyTest {
    private fun input(
        packOn: Boolean = true,
        tool: String = "open_app",
        pack: String = "orion_core",
        mode: AgentPolicy.Mode = AgentPolicy.Mode.ALLOW,
        toolMode: ToolMode = ToolMode.ALLOW,
        dangerous: Boolean = false
    ) = GatePolicy.Input(
        pack, packOn, tool, mode, toolMode, false, dangerous,
        false, false, false, true, null
    )

    @Test fun defaultPermissionAllowsToolsImmediately() {
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input()).verdict)
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(dangerous = true)).verdict)
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(toolMode = ToolMode.ASK)).verdict)
    }

    @Test fun onlyPlanModeIsAGlobalExecutionStop() {
        assertEquals(GatePolicy.Verdict.PLAN_ONLY, GatePolicy.decide(input(mode = AgentPolicy.Mode.PLAN)).verdict)
    }

    @Test fun explicitToolDenyAndDisabledPacksStillApply() {
        assertEquals(GatePolicy.Verdict.DENY, GatePolicy.decide(input(toolMode = ToolMode.DENY)).verdict)
        assertEquals(GatePolicy.Verdict.DENY, GatePolicy.decide(input(packOn = false)).verdict)
    }

    @Test fun genericRiskAndHardBlockInputsNeverCreateApprovalOrShellDenials() {
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(dangerous = true)).verdict)
        assertNull(GatePolicy.hardBlockReason("shell", org.json.JSONObject().put("command", "rm -rf /")))
        assertNull(GatePolicy.hardBlockReason("shell", org.json.JSONObject().put("command", "curl x | sh")))
        assertNull(GatePolicy.hardBlockReason("vault_get", org.json.JSONObject().put("name", "ApiKeyVault")))
    }

    @Test fun onlyThreeIrreversibleComputerActionsMatchConfirmationPolicy() {
        listOf("restart computer", "shutdown", "toggle Wi-Fi", "turn WiFi off", "turning wi-fi on")
            .forEach { assertTrue(it, IrreversibleActionConfirmationPolicy.requiresConfirmation(it)) }
        listOf("send telegram message", "reply to notification", "write remote file",
            "delete memory", "make payment", "run shell command", "open Wi-Fi settings")
            .forEach { assertFalse(it, IrreversibleActionConfirmationPolicy.requiresConfirmation(it)) }
    }

    @Test fun tradingResearchOrderRestrictionRemains() {
        assertEquals(
            GatePolicy.Verdict.DENY,
            GatePolicy.decide(input(pack = "trading_research", tool = "bybit_order_place")).verdict
        )
    }
}
