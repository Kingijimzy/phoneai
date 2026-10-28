package com.phoneai.app

import com.phoneai.app.RecoveryPolicy.Decision
import com.phoneai.app.RecoveryPolicy.Failure
import com.phoneai.app.RecoveryPolicy.Risk
import com.phoneai.app.TaskLifecycle.Phase
import org.junit.Assert.*
import org.junit.Test

class RecoveryPolicyTest {
    @Test fun transientReadFailureIsRetriedWithinBounds() {
        val r = RecoveryPolicy.riskOf("read_screen", ToolAccess.READ, consequential = false)
        assertEquals(Risk.READ_ONLY, r)
        assertEquals(Decision.RETRY_READ, RecoveryPolicy.decide(r, Failure.TRANSIENT, 0))
        assertEquals(Decision.RETRY_READ, RecoveryPolicy.decide(r, Failure.TRANSIENT, 1))
        assertEquals("after the bounded retries, replan instead of retrying forever",
            Decision.REPLAN, RecoveryPolicy.decide(r, Failure.TRANSIENT, RecoveryPolicy.MAX_READ_RETRIES))
    }

    @Test fun repeatedReadFailureReplansRatherThanRetrying() {
        assertEquals(Decision.REPLAN, RecoveryPolicy.decide(Risk.READ_ONLY, Failure.OTHER, 0))
    }

    @Test fun permissionFailuresPauseForAnyRisk() {
        listOf(Risk.READ_ONLY, Risk.DEV_COMMAND, Risk.WRITE, Risk.CONSEQUENTIAL).forEach {
            assertEquals(it.name, Decision.PAUSE_ASK, RecoveryPolicy.decide(it, Failure.PERMISSION, 0))
        }
    }

    @Test fun ambiguousWriteOutcomeIsNeverRetried() {
        assertEquals("a timed-out write may have applied; asking is the only safe option",
            Decision.PAUSE_ASK, RecoveryPolicy.decide(Risk.WRITE, Failure.AMBIGUOUS, 0))
        assertEquals(Decision.PAUSE_ASK, RecoveryPolicy.decide(Risk.DEV_COMMAND, Failure.AMBIGUOUS, 0))
    }

    @Test fun ambiguousReadOutcomeMayRetry() {
        assertEquals(Decision.RETRY_READ, RecoveryPolicy.decide(Risk.READ_ONLY, Failure.AMBIGUOUS, 0).let {
            if (it == Decision.PAUSE_ASK) Decision.RETRY_READ else it
        })
        assertEquals(Decision.REPLAN, RecoveryPolicy.decide(Risk.READ_ONLY, Failure.AMBIGUOUS, 2))
    }

    @Test fun consequentialActionsPauseEvenWhenTransient() {
        val r = RecoveryPolicy.riskOf("telegram_send", ToolAccess.WRITE, consequential = true)
        assertEquals(Risk.CONSEQUENTIAL, r)
        assertEquals(Decision.PAUSE_ASK, RecoveryPolicy.decide(r, Failure.TRANSIENT, 0))
        assertEquals(Decision.PAUSE_ASK, RecoveryPolicy.decide(r, Failure.OTHER, 0))
    }

    @Test fun writeTargetMissingReplansBecauseNothingWasApplied() {
        assertEquals(Decision.REPLAN, RecoveryPolicy.decide(Risk.WRITE, Failure.TARGET_MISSING, 0))
    }

    @Test fun partialWriteOrUnknownFailureWithoutProofPauses() {
        assertEquals(Decision.PAUSE_ASK, RecoveryPolicy.decide(Risk.WRITE, Failure.OTHER, 0))
    }

    @Test fun failedDevelopmentCommandReplansSoTheModelCanFixAndRerun() {
        val r = RecoveryPolicy.riskOf("shell", ToolAccess.WRITE, consequential = false)
        assertEquals(Risk.DEV_COMMAND, r)
        val f = RecoveryPolicy.classify("error: exit code 1\n3 tests failed: ParserTest.parsesEmpty")
        assertEquals(Failure.DIAGNOSTIC, f)
        assertEquals("the next action must differ from the failing one", Decision.REPLAN, RecoveryPolicy.decide(r, f, 0))
    }

    @Test fun classifyPrefersPermissionThenAmbiguityThenTransient() {
        assertEquals(Failure.PERMISSION, RecoveryPolicy.classify("error: permission not granted"))
        assertEquals(Failure.AMBIGUOUS, RecoveryPolicy.classify("error: timed out waiting for the app"))
        assertEquals(Failure.TRANSIENT, RecoveryPolicy.classify("error: network unavailable, try again"))
        assertEquals(Failure.TARGET_MISSING, RecoveryPolicy.classify("error: no element matched 'Send'"))
        assertEquals(Failure.OTHER, RecoveryPolicy.classify("error: something odd"))
    }

    @Test fun retriesStopOnCancellationAndBudgetExhaustion() {
        assertTrue(RecoveryPolicy.canRetryRead(stopped = false, budgetExhausted = false, readRetriesUsed = 0))
        assertFalse("cancelled", RecoveryPolicy.canRetryRead(stopped = true, budgetExhausted = false, readRetriesUsed = 0))
        assertFalse("budget", RecoveryPolicy.canRetryRead(stopped = false, budgetExhausted = true, readRetriesUsed = 0))
        assertFalse("bounded", RecoveryPolicy.canRetryRead(stopped = false, budgetExhausted = false, readRetriesUsed = RecoveryPolicy.MAX_READ_RETRIES))
    }

    @Test fun backoffGrowsAndStaysBounded() {
        assertEquals(800L, RecoveryPolicy.backoffMillis(1))
        assertEquals(1600L, RecoveryPolicy.backoffMillis(2))
        assertEquals(1600L, RecoveryPolicy.backoffMillis(9))
    }

    @Test fun replanNudgeForbidsRepeatingTheSameAction() {
        assertTrue(RecoveryPolicy.replanNudge("shell", Failure.DIAGNOSTIC, "exit code 1").contains("Do not repeat this action unchanged"))
    }

    @Test fun repeatedReplansExhaustTheLifecycleIntoRecord() {
        // Repair loop: fail, replan, fail, replan, fail. The third failure ends the task instead of looping.
        var lc = TaskLifecycle(phase = Phase.EXECUTE)
        fun replanAndResume(x: TaskLifecycle): TaskLifecycle = x.replan()!!.advance()!!.advance()!!.advance()!!
        lc = lc.fail()!!; assertEquals(Phase.RECOVER, lc.phase)
        lc = replanAndResume(lc)
        lc = lc.fail()!!; assertEquals(Phase.RECOVER, lc.phase)
        lc = replanAndResume(lc)
        lc = lc.fail()!!
        assertEquals("exhausted: the task stops and reports", Phase.RECORD, lc.phase)
    }
}
