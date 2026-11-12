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

    @Test fun noModeOrSwitchRefusesATool() {
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(mode = AgentPolicy.Mode.PLAN)).verdict)
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(toolMode = ToolMode.DENY)).verdict)
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(packOn = false)).verdict)
    }

    @Test fun destructiveShellCommandsAreWarningOnlyAtTheCentralGate() {
        val command = "rm -rf /system/confirmed-wipe-test"
        assertNull(GatePolicy.hardBlockReason("shell", org.json.JSONObject().put("command", command)))
        assertNull(GatePolicy.hardBlockReason("shell", org.json.JSONObject().put("command", command).put("confirmed", true)))
        assertEquals(ShellRisk.WARNING, ShellPolicy.classify(command))
        assertNotNull(ShellPolicy.warning(command))
    }

    @Test fun genericRiskAndDestructiveCommandsDoNotCreateExecutionDenials() {
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(dangerous = true)).verdict)
        assertNull(GatePolicy.hardBlockReason("shell", org.json.JSONObject().put("command", "rm -rf /")))
        assertNull(GatePolicy.hardBlockReason("shell", org.json.JSONObject().put("command", "curl x | sh")))
        assertNull(GatePolicy.hardBlockReason("vault_get", org.json.JSONObject().put("name", "ApiKeyVault")))
    }

    @Test fun onlyDeleteAndWipeOperationsMatchConfirmationPolicy() {
        listOf("delete old files", "wipe app data", "empty the trash", "factory reset the phone",
            "remove project files", "clear chat history")
            .forEach { assertNotNull(it, IrreversibleActionConfirmationPolicy.destructiveRequestDescription(it)) }
        listOf("restart app", "shutdown service", "toggle Wi-Fi settings screen",
            "send telegram message", "reply to notification", "write remote file",
            "make payment", "run shell command", "do not delete the backup")
            .forEach { assertNull(it, IrreversibleActionConfirmationPolicy.destructiveRequestDescription(it)) }
        assertFalse(IrreversibleActionConfirmationPolicy.requiresConfirmation("computer_settings restart"))
    }

    @Test fun tradingResearchOrderRestrictionRemains() {
        assertEquals(
            GatePolicy.Verdict.DENY,
            GatePolicy.decide(input(pack = "trading_research", tool = "bybit_order_place")).verdict
        )
    }
}
