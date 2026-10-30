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

    @Test fun timedOutReadIsRetriedBecauseReadsHaveNoSideEffects() {
        assertEquals(Decision.RETRY_READ, RecoveryPolicy.decide(Risk.READ_ONLY, Failure.AMBIGUOUS, 0))
        assertEquals(Decision.REPLAN, RecoveryPolicy.decide(Risk.READ_ONLY, Failure.AMBIGUOUS, RecoveryPolicy.MAX_READ_RETRIES))
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

    @Test fun ownerConfiguredBackoffUsesBaseAndBoundedExponentialDelay() {
        assertEquals(15_000L, RecoveryPolicy.backoffMillis(1, 15))
        assertEquals(30_000L, RecoveryPolicy.backoffMillis(2, 15))
        assertEquals(30_000L, RecoveryPolicy.backoffMillis(99, 15))
        assertEquals(300_000L, RecoveryPolicy.backoffMillis(1, 999))
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

    private fun isErr(r: String) = r.startsWith("error")

    @Test fun retryReadRecoversFromATransientFailure() {
        val results = ArrayDeque(listOf("ok: 3 results"))
        val slept = mutableListOf<Long>()
        val seen = mutableListOf<Int>()
        val out = RecoveryPolicy.retryRead("error: network unavailable, try again", ::isErr, { false }, { false },
            { slept += it }, { n, _ -> seen += n }) { results.removeFirst() }
        assertEquals("ok: 3 results", out.result)
        assertEquals(1, out.retries)
        assertEquals(listOf(800L), slept)
        assertEquals(listOf(1), seen)
    }

    @Test fun retryReadStopsAfterTheBoundWhenFailureRepeats() {
        var calls = 0
        val out = RecoveryPolicy.retryRead("error: 503 unavailable", ::isErr, { false }, { false }, { }, { _, _ -> }) {
            calls++; "error: 503 unavailable"
        }
        assertEquals(RecoveryPolicy.MAX_READ_RETRIES, calls)
        assertEquals(RecoveryPolicy.MAX_READ_RETRIES, out.retries)
        assertTrue(isErr(out.result))
    }

    @Test fun retryReadNeverRepeatsPermissionOrNonTransientFailures() {
        var calls = 0
        val perm = RecoveryPolicy.retryRead("error: permission not granted", ::isErr, { false }, { false }, { }, { _, _ -> }) { calls++; "ok" }
        val other = RecoveryPolicy.retryRead("error: weird thing", ::isErr, { false }, { false }, { }, { _, _ -> }) { calls++; "ok" }
        assertEquals(0, calls)
        assertEquals(0, perm.retries); assertEquals(0, other.retries)
    }

    @Test fun retryReadDoesNothingOnSuccess() {
        var calls = 0
        val out = RecoveryPolicy.retryRead("ok", ::isErr, { false }, { false }, { }, { _, _ -> }) { calls++; "ok" }
        assertEquals(0, calls); assertEquals(0, out.retries); assertEquals("ok", out.result)
    }

    @Test fun cancellationStopsRetriesImmediately() {
        var calls = 0
        val before = RecoveryPolicy.retryRead("error: timed out", ::isErr, { true }, { false }, { }, { _, _ -> }) { calls++; "ok" }
        assertEquals(0, calls); assertEquals(0, before.retries)
        // Cancelled during the backoff: no new dispatch starts.
        var cancelled = false
        val during = RecoveryPolicy.retryRead("error: timed out", ::isErr, { cancelled }, { false }, { cancelled = true }, { _, _ -> }) { calls++; "ok" }
        assertEquals(0, calls)
        assertTrue(isErr(during.result))
    }

    @Test fun budgetExhaustionStopsRetries() {
        var calls = 0
        val out = RecoveryPolicy.retryRead("error: timed out", ::isErr, { false }, { true }, { }, { _, _ -> }) { calls++; "ok" }
        assertEquals(0, calls); assertEquals(0, out.retries)
    }

    @Test fun writesAndConsequentialActionsAreNeverRetriedAutomatically() {
        assertEquals(Decision.PAUSE_ASK, RecoveryPolicy.decide(Risk.WRITE, Failure.AMBIGUOUS, 0))
        assertEquals(Decision.PAUSE_ASK, RecoveryPolicy.decide(Risk.CONSEQUENTIAL, Failure.TRANSIENT, 0))
        assertEquals(Decision.PAUSE_ASK, RecoveryPolicy.decide(Risk.CONSEQUENTIAL, Failure.TARGET_MISSING, 0))
    }

    @Test fun failedTestThenRepairThenRerunIsAllowedByReplan() {
        assertEquals(Risk.DEV_COMMAND, RecoveryPolicy.riskOf("shell", ToolAccess.WRITE, consequential = false))
        assertEquals(Decision.REPLAN, RecoveryPolicy.decide(Risk.DEV_COMMAND, RecoveryPolicy.classify("exit code 1: 2 tests failed"), 0))
    }
}
