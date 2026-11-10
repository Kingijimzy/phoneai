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
    ) = GatePolicy.Input(pack, packOn, tool, mode, toolMode, false, dangerous, false, false, false, true, null)

    @Test fun genericModesAndPackSwitchesNeverBlockPartnerTools() {
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input()).verdict)
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(dangerous = true)).verdict)
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(toolMode = ToolMode.ASK)).verdict)
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(toolMode = ToolMode.DENY)).verdict)
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(packOn = false)).verdict)
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(mode = AgentPolicy.Mode.PLAN)).verdict)
    }

    @Test fun hardBlocksAndTradingOrderRestrictionsRemain() {
        assertEquals(GatePolicy.Verdict.DENY, GatePolicy.decide(input().copy(hardBlockReason = "Android OS denied access")).verdict)
        assertEquals(GatePolicy.Verdict.DENY, GatePolicy.decide(input(pack = "trading_research", tool = "bybit_order_place")).verdict)
        assertNotNull(GatePolicy.hardBlockReason("shell", org.json.JSONObject().put("command", "rm -rf /")))
        assertNull(GatePolicy.hardBlockReason("shell", org.json.JSONObject().put("command", "rm old-build")))
    }

    @Test fun onlyTradingDeskMutationsAndDestructiveDeletionNeedConfirmation() {
        assertTrue(AgentHooks.requiresConfirmation("paper_trade", org.json.JSONObject()))
        assertTrue(AgentHooks.requiresConfirmation("strategy_save", org.json.JSONObject()))
        assertFalse(AgentHooks.requiresConfirmation("bybit_public_ticker", org.json.JSONObject()))
        assertFalse(AgentHooks.requiresConfirmation("web_search", org.json.JSONObject()))
        assertTrue(AgentHooks.requiresConfirmation("workspace_delete", org.json.JSONObject()))
        assertTrue(AgentHooks.requiresConfirmation("shell", org.json.JSONObject().put("command", "rm old-build")))
        assertTrue(AgentHooks.requiresConfirmation("python_exec", org.json.JSONObject().put("code", "import os\nos.remove('/workspace/old.txt')")))
        assertFalse(AgentHooks.requiresConfirmation("shell", org.json.JSONObject().put("command", "ls -la")))
    }
}
