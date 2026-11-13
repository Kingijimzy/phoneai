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
        dangerous: Boolean = false,
        approved: Boolean = false,
        trusted: Boolean = false,
        hardBlock: String? = null
    ) = GatePolicy.Input(pack, packOn, tool, mode, toolMode, false, dangerous,
        approved, trusted, false, true, hardBlock)

    @Test fun normalRegisteredToolIsAllowed() {
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input()).verdict)
    }

    @Test fun planModeDoesNotExecute() {
        assertEquals(GatePolicy.Verdict.PLAN_ONLY,
            GatePolicy.decide(input(mode = AgentPolicy.Mode.PLAN)).verdict)
    }

    @Test fun explicitToolDenyIsEnforced() {
        assertEquals(GatePolicy.Verdict.DENY,
            GatePolicy.decide(input(toolMode = ToolMode.DENY)).verdict)
    }

    @Test fun askModeRequiresApprovalOrTrustedSession() {
        assertEquals(GatePolicy.Verdict.DENY,
            GatePolicy.decide(input(toolMode = ToolMode.ASK)).verdict)
        assertEquals(GatePolicy.Verdict.ALLOW,
            GatePolicy.decide(input(toolMode = ToolMode.ASK, approved = true)).verdict)
        assertEquals(GatePolicy.Verdict.ALLOW,
            GatePolicy.decide(input(toolMode = ToolMode.ASK, trusted = true)).verdict)
    }

    @Test fun disabledPackAndHardBlockAreEnforced() {
        assertEquals(GatePolicy.Verdict.DENY, GatePolicy.decide(input(packOn = false)).verdict)
        assertEquals(GatePolicy.Verdict.DENY, GatePolicy.decide(input(hardBlock = "blocked")).verdict)
    }

    @Test fun consequentialActionsUseDedicatedConfirmationPolicyWithoutGlobalAutonomyVeto() {
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(dangerous = true)).verdict)
    }

    @Test fun tradingResearchOrderRestrictionRemains() {
        assertEquals(GatePolicy.Verdict.DENY,
            GatePolicy.decide(input(pack = "trading_research", tool = "bybit_order_place")).verdict)
    }

    @Test fun destructiveShellCommandsAreStillClassified() {
        val command = "rm -rf /system/confirmed-wipe-test"
        assertEquals(ShellRisk.WARNING, ShellPolicy.classify(command))
        assertNotNull(ShellPolicy.warning(command))
    }

    @Test fun destructiveRequestConfirmationPolicyRemainsNarrow() {
        listOf("delete old files", "wipe app data", "empty the trash", "factory reset the phone",
            "remove project files", "clear chat history")
            .forEach { assertNotNull(it, IrreversibleActionConfirmationPolicy.destructiveRequestDescription(it)) }
        listOf("restart app", "shutdown service", "toggle Wi-Fi settings screen",
            "send telegram message", "reply to notification", "write remote file",
            "make payment", "run shell command", "do not delete the backup")
            .forEach { assertNull(it, IrreversibleActionConfirmationPolicy.destructiveRequestDescription(it)) }
        assertFalse(IrreversibleActionConfirmationPolicy.requiresConfirmation("computer_settings restart"))
    }
}
