package com.phoneai.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OrionMissionControlTest {
    @Before fun reset() = OrionMissionControl.clearForTest()
    private val brain = OrionMissionControl.Owner("brain-chat", OrionMissionControl.OwnerKind.BRAIN)
    private val w1 = OrionMissionControl.Owner("w1", OrionMissionControl.OwnerKind.WORKER)
    private val w2 = OrionMissionControl.Owner("w2", OrionMissionControl.OwnerKind.WORKER)

    @Test fun beginWalksToExecuteAndRecordsMode() {
        val m = OrionMissionControl.begin("chat", "fix the parser", parentId = null, mode = "FULL")
        assertEquals(TaskLifecycle.Phase.EXECUTE, m.lifecycle.phase)
        assertEquals("FULL", m.mode); assertTrue(m.active)
    }

    @Test fun onlyOneOwnerAtATime() {
        val m = OrionMissionControl.begin("chat", "x task", parentId = null)
        assertTrue(OrionMissionControl.claim(m.id, w1))
        assertFalse(OrionMissionControl.claim(m.id, w2))
        assertTrue(OrionMissionControl.claim(m.id, w1))
        OrionMissionControl.release(m.id, w1)
        assertTrue(OrionMissionControl.claim(m.id, w2))
    }

    @Test fun verifiedFinishCompletesButUnverifiedNeverClaimsDone() {
        val a = OrionMissionControl.begin("chat", "a job", parentId = null)
        val done = OrionMissionControl.finish(a.id, ok = true, verified = true, evidence = "file exists")!!
        assertEquals(TaskLifecycle.Phase.RECORD, done.lifecycle.phase); assertEquals("verified", done.outcome)
        val b = OrionMissionControl.begin("chat", "b job", parentId = null)
        val un = OrionMissionControl.finish(b.id, ok = true, verified = false, evidence = "said it worked")!!
        assertEquals(TaskLifecycle.Phase.BLOCKED, un.lifecycle.phase); assertEquals("unverified", un.outcome)
        assertFalse(un.active)
    }

    @Test fun failureIsClosedAndVisibleNotDangling() {
        val m = OrionMissionControl.begin("chat", "c job", parentId = null)
        val f = OrionMissionControl.finish(m.id, ok = false, verified = false, evidence = "boom")!!
        assertEquals("failed", f.outcome); assertFalse(f.active)
        assertTrue(OrionMissionControl.active().isEmpty())
    }

    @Test fun cancellingAnObjectiveStopsItsWorkersAndInvokesTheirCancellers() {
        val root = OrionMissionControl.begin("chat", "big objective", parentId = null)
        OrionMissionControl.claim(root.id, brain)
        val child = OrionMissionControl.begin("worker", "sub job", parentId = root.id)
        var stopped = false
        OrionMissionControl.claim(child.id, w1) { stopped = true }
        assertEquals(2, OrionMissionControl.cancel(root.id))
        assertTrue(stopped)
        assertTrue(OrionMissionControl.active().isEmpty())
        assertEquals(TaskLifecycle.Phase.CANCELLED, OrionMissionControl.get(child.id)!!.lifecycle.phase)
    }

    @Test fun spawnedWorkAttachesUnderTheCurrentMission() {
        val root = OrionMissionControl.begin("chat", "root", parentId = null)
        val child = OrionMissionControl.within(root.id) { OrionMissionControl.begin("worker", "child job") }
        assertEquals(root.id, child.parentId)
        assertEquals(listOf(child.id), OrionMissionControl.children(root.id).map { it.id })
    }

    @Test fun finishedWorkerSurfacesOnceForInitiative() {
        val w = OrionMissionControl.begin("worker", "research the thing", parentId = null)
        OrionMissionControl.finish(w.id, ok = true, verified = false, evidence = "report ready")
        val first = OrionMissionControl.attention()
        assertEquals(w.id, first?.id)
        OrionMissionControl.markAnnounced(w.id)
        assertNull(OrionMissionControl.attention())
    }

    @Test fun cancelledWorkIsNotAnnounced() {
        val w = OrionMissionControl.begin("worker", "never mind", parentId = null)
        OrionMissionControl.cancel(w.id)
        assertNull(OrionMissionControl.attention())
    }
    @Test fun unrelatedActiveMissionsStayOutOfNewConversationContext() {
        val trading = OrionMissionControl.begin("chat", "Analyse BTCUSDT liquidity sweep", parentId = null)
        val coding = OrionMissionControl.begin("chat", "Repair Android Compose chat restoration", parentId = null)

        val evidence = OrionMissionControl.evidence("Continue BTCUSDT liquidity sweep analysis")
        assertTrue(evidence.contains(trading.id))
        assertFalse(evidence.contains(coding.id))

        val summary = OrionMissionControl.summary("BTCUSDT liquidity sweep analysis")
        assertTrue(summary.contains(trading.id))
        assertFalse(summary.contains(coding.id))

        assertEquals("", OrionMissionControl.evidence(""))
        assertEquals("", OrionMissionControl.summary(""))
        assertEquals("", OrionMissionControl.summary("Hey bro"))
        assertEquals("", OrionMissionControl.summary("Fix my phone camera focus"))
    }

}
