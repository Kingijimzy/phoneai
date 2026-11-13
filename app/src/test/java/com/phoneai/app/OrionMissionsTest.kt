package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionMissionsTest {
    private fun m(id: String, p: Int = 5, state: OrionMissions.State = OrionMissions.State.OPEN, attempts: Int = 0, nextAt: Long = 0, started: Long = 0, created: Long = 1) =
        OrionMissions.Mission(id, "title $id", "test", p, state, attempts, nextAt, started, "", created)

    @Test fun picksHighestPriorityDueMission() {
        val l = listOf(m("a", 3), m("b", 8), m("c", 9, nextAt = 5_000))
        assertEquals("b", OrionMissions.pickNext(l, 1_000)?.id)
        assertEquals("c", OrionMissions.pickNext(l, 6_000)?.id)
    }

    @Test fun skipsRunningDoneAndBlocked() {
        val l = listOf(m("a", state = OrionMissions.State.RUNNING), m("b", state = OrionMissions.State.DONE), m("c", state = OrionMissions.State.BLOCKED))
        assertNull(OrionMissions.pickNext(l, 1_000))
    }

    @Test fun interruptedRunningMissionIsReopened() {
        val l = listOf(m("a", state = OrionMissions.State.RUNNING, started = 1_000))
        val r = OrionMissions.reconcile(l, 1_000 + OrionMissions.STALE_RUNNING_MS + 1)
        assertEquals(OrionMissions.State.OPEN, r.single().state)
        val fresh = OrionMissions.reconcile(l, 2_000)
        assertEquals(OrionMissions.State.RUNNING, fresh.single().state)
    }

    @Test fun outcomeIsReadFromTheReply() {
        assertEquals(OrionMissions.Outcome.COMPLETE, OrionMissions.outcomeOf("Done.\nMISSION COMPLETE: fixed gmail, verified by a list call"))
        assertEquals(OrionMissions.Outcome.BLOCKED, OrionMissions.outcomeOf("MISSION BLOCKED: need the Telegram token"))
        assertEquals(OrionMissions.Outcome.UNKNOWN, OrionMissions.outcomeOf("I looked into it."))
    }

    @Test fun failedAttemptsBackOffThenBlock() {
        val first = OrionMissions.afterAttempt(m("a", attempts = 1, state = OrionMissions.State.RUNNING), OrionMissions.Outcome.UNKNOWN, "", 1_000)
        assertEquals(OrionMissions.State.OPEN, first.state)
        assertEquals(1_000 + OrionMissions.backoffMs(1), first.nextAt)
        val last = OrionMissions.afterAttempt(m("a", attempts = OrionMissions.MAX_ATTEMPTS, state = OrionMissions.State.RUNNING), OrionMissions.Outcome.UNKNOWN, "", 1_000)
        assertEquals(OrionMissions.State.BLOCKED, last.state)
    }

    @Test fun completeAndBlockedCloseTheMission() {
        val run = m("a", attempts = 1, state = OrionMissions.State.RUNNING)
        assertEquals(OrionMissions.State.DONE, OrionMissions.afterAttempt(run, OrionMissions.Outcome.COMPLETE, "ok", 1).state)
        assertEquals(OrionMissions.State.BLOCKED, OrionMissions.afterAttempt(run, OrionMissions.Outcome.BLOCKED, "need key", 1).state)
    }

    @Test fun titlesNormalizeForDedupe() {
        assertEquals(OrionMissions.normalize("Self-repair: 'gmail' keeps failing!"), OrionMissions.normalize("self repair gmail keeps failing"))
    }

    @Test fun missionPromptDemandsVerifiedEnding() {
        val p = OrionMissions.prompt(m("a"))
        assertTrue(p.contains("MISSION COMPLETE"))
        assertTrue(p.contains("MISSION BLOCKED"))
        assertTrue(p.contains("Attempt 0 of"))
    }
}
