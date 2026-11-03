package com.phoneai.app

import com.phoneai.app.TaskLifecycle.Phase
import org.junit.Assert.*
import org.junit.Test

class TaskLifecycleTest {
    private fun walkToVerify(): TaskLifecycle {
        var t = TaskLifecycle()
        repeat(5) { t = t.advance()!! } // UNDERSTAND→PLAN→CHECK→AUTH→EXECUTE→VERIFY
        assertEquals(Phase.VERIFY, t.phase)
        return t
    }

    @Test fun normalPathEndsInRecordAfterVerification() {
        var t = walkToVerify()
        assertNull("cannot complete without verification", t.advance())
        t = t.verifiedOk()!!.advance()!!
        assertEquals(Phase.COMPLETE, t.phase)
        t = t.advance()!!
        assertEquals(Phase.RECORD, t.phase)
        assertTrue(t.isTerminal)
    }

    @Test fun executeCannotBeSkippedAndVerifyCannotBeClaimedEarly() {
        val t = TaskLifecycle().advance()!! // PLAN
        assertNull(t.verifiedOk())
        assertNull(t.replan())
    }

    @Test fun failureRecoversBoundedlyThenRecords() {
        var t = walkToVerify()
        t = t.fail()!!
        assertEquals(Phase.RECOVER, t.phase)
        assertEquals(1, t.recoveries)
        t = t.replan()!!.advance()!!.advance()!!.advance()!! // PLAN→CHECK→AUTH→EXECUTE
        assertEquals(Phase.EXECUTE, t.phase)
        t = t.advance()!!
        t = t.fail()!!
        assertEquals(2, t.recoveries)
        t = t.replan()!!
        repeat(3) { t = t.advance()!! } // PLAN→CHECK→AUTH→EXECUTE
        t = t.fail()!!
        assertEquals("third failure ends the task instead of retrying forever", Phase.RECORD, t.phase)
        assertFalse(t.verified)
    }

    @Test fun failIsOnlyValidDuringExecuteOrVerify() {
        assertNull(TaskLifecycle().fail())
        assertNull(TaskLifecycle(phase = Phase.PLAN).fail())
    }

    @Test fun blockedExecutionResumesByReplanningNotReplayingAnAction() {
        val exec = TaskLifecycle(phase = Phase.EXECUTE)
        val blocked = exec.block()!!
        assertEquals(Phase.BLOCKED, blocked.phase)
        assertEquals(Phase.EXECUTE, blocked.blockedFrom)
        val resumed = blocked.resume()!!
        assertEquals(Phase.PLAN, resumed.phase)
        assertNull(resumed.blockedFrom)
        assertFalse(resumed.verified)
        assertNull("only a blocked task can resume", exec.resume())
    }

    @Test fun blockedAuthorizationResumesAtAuthorizationAndCancellationIsTerminal() {
        val blocked = TaskLifecycle(phase = Phase.AUTHORIZE).block()!!
        assertEquals(Phase.AUTHORIZE, blocked.resume()!!.phase)

        val cancelled = blocked.cancel()!!
        assertEquals(Phase.CANCELLED, cancelled.phase)
        assertTrue(cancelled.isTerminal)
        assertNull(cancelled.resume())
        assertNull(cancelled.advance())
    }

    @Test fun cannotBlockTerminalOrRecoveringTasks() {
        assertNull(TaskLifecycle(phase = Phase.RECORD).block())
        assertNull(TaskLifecycle(phase = Phase.CANCELLED).block())
        assertNull(TaskLifecycle(phase = Phase.RECOVER).block())
    }

    @Test fun cancelEndsAnyLiveTaskAndIsFinal() {
        val c = TaskLifecycle(phase = Phase.AUTHORIZE).cancel()!!
        assertEquals(Phase.CANCELLED, c.phase)
        assertTrue(c.isTerminal)
        assertNull(c.advance())
        assertNull(c.resume())
        assertNull(TaskLifecycle(phase = Phase.RECORD).cancel())
    }
}
