package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionAutonomyPolicyTest {
    @Test fun modesMapToExpectedExistingToolPolicies() {
        assertEquals("read_only", OrionAutonomyPolicy.preset(OrionMode.TALK))
        assertEquals("balanced", OrionAutonomyPolicy.preset(OrionMode.ASSIST))
        assertEquals("autonomous", OrionAutonomyPolicy.preset(OrionMode.AUTOPILOT))
        assertEquals("careful", OrionAutonomyPolicy.preset(OrionMode.BUILDER))
    }

    @Test fun talkModeBlocksEveryWriteToolRegardlessOfPerToolAllowOverrides() {
        assertTrue(OrionAutonomyPolicy.blocksWrite(OrionMode.TALK, ToolAccess.WRITE))
        assertFalse(OrionAutonomyPolicy.blocksWrite(OrionMode.TALK, ToolAccess.READ))
        assertFalse(OrionAutonomyPolicy.blocksWrite(OrionMode.AUTOPILOT, ToolAccess.WRITE))
        assertFalse(OrionAutonomyPolicy.blocksWrite(OrionMode.BUILDER, null))
    }

    @Test fun executionLimitsAreClampedToBoundedValues() {
        val c = OrionAutonomyPolicy.clamp(OrionAutonomyConfig(
            mode = OrionMode.AUTOPILOT,
            maxToolSteps = 500,
            maxAiRequests = -3,
            maxRetries = 100
        ))
        assertEquals(40, c.maxToolSteps)
        assertEquals(1, c.maxAiRequests)
        assertEquals(4, c.maxRetries)
    }

    @Test fun autopilotRunsRoutinePythonWithoutPromptButKeepsConsequentialActionsGated() {
        fun decision(dangerous: Boolean) = GatePolicy.decide(GatePolicy.Input(
            pack = "workspace_shell",
            packOn = true,
            tool = "python_exec",
            mode = AgentPolicy.Mode.TRUSTED,
            toolMode = ToolMode.ASK,
            autonomous = true,
            dangerous = dangerous,
            approvedThisAction = false,
            trustedForPack = false,
            allowlisted = false,
            askedThisTask = false
        )).verdict

        assertEquals(GatePolicy.Verdict.ALLOW, decision(dangerous = false))
        assertEquals(GatePolicy.Verdict.NEED_APPROVAL, decision(dangerous = true))
    }

    @Test fun zeroConfiguredRetriesNeverRetriesReadFailure() {
        assertEquals(
            RecoveryPolicy.Decision.REPLAN,
            RecoveryPolicy.decide(RecoveryPolicy.Risk.READ_ONLY, RecoveryPolicy.Failure.TRANSIENT, 0, 0)
        )
        assertFalse(RecoveryPolicy.canRetryRead(false, false, 0, 0))
    }

    @Test fun dynamicRequestLimitCanBeLoweredDuringAnActiveAgentSession() {
        var limit = 3
        val budget = CommandRequestBudget(maxRequests = 15, maxRequestsProvider = { limit })
        assertTrue(budget.tryReserve())
        assertTrue(budget.tryReserve())
        limit = 2
        assertFalse(budget.tryReserve())
        assertEquals(2, budget.used())
    }
}
