package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class GatePolicyTest {
    private fun input(
        packOn: Boolean = true, tool: String = "open_app", pack: String = "orion_core",
        mode: AgentPolicy.Mode = AgentPolicy.Mode.ALLOWLIST, toolMode: ToolMode = ToolMode.ALLOW,
        autonomous: Boolean = false, dangerous: Boolean = false, approved: Boolean = false,
        trusted: Boolean = false, allowlisted: Boolean = true, asked: Boolean = true
    ) = GatePolicy.Input(pack, packOn, tool, mode, toolMode, autonomous, dangerous, approved, trusted, allowlisted, asked)

    @Test fun offPackIsDeniedWhateverElseIsSet() {
        assertEquals(GatePolicy.Verdict.DENY, GatePolicy.decide(input(packOn = false, approved = true)).verdict)
    }

    @Test fun tradingOrdersAreAlwaysDenied() {
        assertEquals(GatePolicy.Verdict.DENY, GatePolicy.decide(input(pack = "trading_research", tool = "bybit_order_place", autonomous = true)).verdict)
    }

    @Test fun userDenyBeatsEverythingElse() {
        assertEquals(GatePolicy.Verdict.DENY, GatePolicy.decide(input(toolMode = ToolMode.DENY, approved = true, autonomous = true)).verdict)
    }

    @Test fun planModeNeverExecutes() {
        assertEquals(GatePolicy.Verdict.PLAN_ONLY, GatePolicy.decide(input(mode = AgentPolicy.Mode.PLAN, autonomous = true)).verdict)
    }

    @Test fun approvedActionRuns() {
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(mode = AgentPolicy.Mode.ASK, dangerous = true, approved = true)).verdict)
    }

    @Test fun askToolPromptsOncePerTask() {
        assertEquals(GatePolicy.Verdict.ASK_TOOL, GatePolicy.decide(input(toolMode = ToolMode.ASK, asked = false)).verdict)
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(toolMode = ToolMode.ASK, asked = true)).verdict)
    }

    @Test fun autonomousSkipsTheToolAskButNotConsequentialGates() {
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(toolMode = ToolMode.ASK, autonomous = true, asked = false)).verdict)
        assertEquals(GatePolicy.Verdict.NEED_APPROVAL, GatePolicy.decide(input(autonomous = true, dangerous = true, mode = AgentPolicy.Mode.ASK)).verdict)
    }

    @Test fun consequentialActionNeedsApprovalUnlessTrusted() {
        assertEquals(GatePolicy.Verdict.NEED_APPROVAL, GatePolicy.decide(input(dangerous = true)).verdict)
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(dangerous = true, trusted = true)).verdict)
    }

    @Test fun autonomousAllowsRoutineSteps() {
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(autonomous = true, mode = AgentPolicy.Mode.ASK)).verdict)
    }

    @Test fun explicitlyAllowedToolDoesNotHitDuplicateGlobalApprovalGate() {
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(mode = AgentPolicy.Mode.ASK, toolMode = ToolMode.ALLOW)).verdict)
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(mode = AgentPolicy.Mode.ALLOWLIST, toolMode = ToolMode.ALLOW, allowlisted = false)).verdict)
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(mode = AgentPolicy.Mode.TRUSTED, toolMode = ToolMode.ALLOW, trusted = false)).verdict)
    }

    @Test fun askSettingAllowsTheToolAfterItsOneTaskApproval() {
        assertEquals(GatePolicy.Verdict.ASK_TOOL, GatePolicy.decide(input(toolMode = ToolMode.ASK, asked = false)).verdict)
        assertEquals(GatePolicy.Verdict.ALLOW, GatePolicy.decide(input(mode = AgentPolicy.Mode.ASK, toolMode = ToolMode.ASK, asked = true)).verdict)
    }

    @Test fun globalModesCannotOverrideExplicitToolDenial() {
        assertEquals(GatePolicy.Verdict.DENY, GatePolicy.decide(input(mode = AgentPolicy.Mode.TRUSTED, toolMode = ToolMode.DENY, trusted = true)).verdict)
    }

    @Test fun offMessageNamesTheFix() {
        assertTrue(GatePolicy.decide(input(packOn = false, pack = "workspace_shell")).reason.contains("Agent control > Packs > Workspace shell"))
    }
}
