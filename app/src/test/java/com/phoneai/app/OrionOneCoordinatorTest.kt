package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionOneCoordinatorTest {
    @Test fun liveSnapshotShowsStagesMissionsAndSteps() {
        val live = OrionEntityLoop.Live("chat", setOf(OrionEntityLoop.Stage.OBSERVE, OrionEntityLoop.Stage.RECALL), 1_000L)
        val snap = OrionLiveView.build(live, emptyList(), emptyList(), emptyList(), 5_000L)
        assertTrue(snap.busy)
        assertEquals(8, snap.stages.size)
        assertTrue(snap.stages.first { it.first == "recall" }.second)
        assertFalse(snap.stages.first { it.first == "act" }.second)
        assertFalse(snap.empty)
    }

    @Test fun idleSnapshotIsEmptySoTheCardHides() {
        assertTrue(OrionLiveView.build(null, emptyList(), emptyList(), emptyList(), 0L).empty)
        val ended = OrionEntityLoop.Live("voice", emptySet(), 1L, endedAt = 2L)
        assertTrue(OrionLiveView.build(ended, emptyList(), emptyList(), emptyList(), 3L).empty)
    }

    @Test fun liveStateFollowsTheLoop() {
        OrionEntityLoop.clearForTest()
        OrionEntityLoop.begin("worker")
        OrionEntityLoop.mark(OrionEntityLoop.Stage.ACT)
        assertTrue(OrionEntityLoop.live!!.reached.contains(OrionEntityLoop.Stage.ACT))
        OrionEntityLoop.end()
        assertTrue(OrionEntityLoop.live!!.endedAt > 0L)
    }

    @Test fun noAgentIsRunOutsideTheCoordinator() {
        val dir = java.io.File("src/main/java/com/phoneai/app")
        val direct = dir.listFiles { f -> f.extension == "kt" }!!
            .filter { Regex("\\bagent\\.run\\(").containsMatchIn(it.readText()) }
            .filter { !Regex("OrionCoordinator\\.run\\(").containsMatchIn(it.readText()) }.map { it.name }
        assertTrue("files running an agent outside the coordinator: $direct", direct.isEmpty())
    }
}
