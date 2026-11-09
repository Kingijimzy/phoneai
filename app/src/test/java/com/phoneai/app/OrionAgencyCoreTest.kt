package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionAgencyCoreTest {

    @Test
    fun backgroundNoiseDetected() {
        assertTrue(OrionMissionLock.isBackgroundNoise("work=soul-watch; key=soul-watch"))
        assertTrue(OrionMissionLock.isBackgroundNoise("startup-context recovery"))
        assertFalse(OrionMissionLock.isBackgroundNoise("scrape apkvision for red dead file size"))
    }

    @Test
    fun recoverySuggestsWebAfterSportsFail() {
        val p = OrionForcedRecovery.afterFailure(
            "atletico vs barca scoreline",
            "live_sports",
            "error: unavailable",
            0
        )
        assertTrue(p.shouldRetry)
        assertTrue(p.nextToolHint!!.contains("web", ignoreCase = true))
    }

    @Test
    fun recoveryGivesUpAfterTwoAttempts() {
        val p = OrionForcedRecovery.afterFailure("anything", "web_search", "error", 2)
        assertFalse(p.shouldRetry)
        assertTrue(p.userMessageIfGiveUp!!.isNotBlank())
    }

    @Test
    fun softUpdateUnknownObjectiveDoesNotThrow() {
        val g = OrionObjectiveGraph.Graph(
            "task",
            listOf(OrionObjectiveGraph.Objective("main", "Do it", status = OrionObjectiveGraph.Status.RUNNING))
        )
        // Must not throw
        val next = OrionObjectiveGraph.updateStatusSoft(g, "apkvision_size", OrionObjectiveGraph.Status.FAILED, "")
        assertEquals(1, next.nodes.size)
        assertEquals("main", next.nodes[0].id)
    }
}
