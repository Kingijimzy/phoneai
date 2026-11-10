package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionAutonomyPolicyTest {
    @Test fun modesMapToExpectedExistingToolPolicies() {
        assertEquals("balanced", OrionAutonomyPolicy.preset(OrionMode.TALK))
        assertEquals("balanced", OrionAutonomyPolicy.preset(OrionMode.ASSIST))
        assertEquals("autonomous", OrionAutonomyPolicy.preset(OrionMode.AUTOPILOT))
        assertEquals("careful", OrionAutonomyPolicy.preset(OrionMode.BUILDER))
    }

    @Test fun talkModeUsesPerToolPermissionsInsteadOfBlockingAllWrites() {
        assertFalse(OrionAutonomyPolicy.blocksWrite(OrionMode.TALK, ToolAccess.WRITE))
        assertFalse(OrionAutonomyPolicy.blocksWrite(OrionMode.TALK, ToolAccess.READ))
        assertFalse(OrionAutonomyPolicy.blocksWrite(OrionMode.AUTOPILOT, ToolAccess.WRITE))
        assertFalse(OrionAutonomyPolicy.blocksWrite(OrionMode.BUILDER, null))
    }

    @Test fun executionLimitsAreClampedToBoundedValues() {
        val c = OrionAutonomyPolicy.clamp(OrionAutonomyConfig(
            mode = OrionMode.AUTOPILOT,
            maxToolSteps = 5_000_000,
            maxAiRequests = -3,
            maxRetries = 100
        ))
        assertEquals(100_000, c.maxToolSteps)
        assertEquals(1, c.maxAiRequests)
        assertEquals(4, c.maxRetries)
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
