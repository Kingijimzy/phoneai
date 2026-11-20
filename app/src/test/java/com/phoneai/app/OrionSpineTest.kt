package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionSpineTest {
    private val models = listOf("alpha-8b", "beta-70b", "gamma-12b")

    @Test fun deepPicksMeasuredBestOverNamePower() {
        val q = mapOf("alpha-8b" to 0.95, "beta-70b" to 0.6, "gamma-12b" to 0.7)
        val pick = OrionDifficulty.pick(ModelRouter.Tier.DEEP, "gamma-12b", models, { q[it] })
        assertEquals("alpha-8b", pick)
    }

    @Test fun deepKeepsCurrentWhenMeasuredBestIsNotClearlyBetter() {
        val q = mapOf("alpha-8b" to 0.72, "gamma-12b" to 0.70)
        assertNull(OrionDifficulty.pick(ModelRouter.Tier.DEEP, "gamma-12b", models, { q[it] }))
    }

    @Test fun fastPicksMeasuredLowestLatency() {
        val ms = mapOf("alpha-8b" to 300.0, "beta-70b" to 2500.0, "gamma-12b" to 1500.0)
        assertEquals("alpha-8b", OrionDifficulty.pick(ModelRouter.Tier.FAST, "beta-70b", models, firstTokenMs = { ms[it] }))
    }

    @Test fun unmeasuredFallsBackToNameBasedBehaviour() {
        assertEquals(OrionDifficulty.pick(ModelRouter.Tier.DEEP, "alpha-8b", models),
            OrionDifficulty.pick(ModelRouter.Tier.DEEP, "alpha-8b", models, { null }, { null }))
    }

    @Test fun evidenceBlockCarriesObjectiveAndLastEvidence() {
        OrionMissionControl.clearForTest()
        val m = OrionMissionControl.begin("chat", "build the settings page", parentId = null)
        OrionMissionControl.finish(m.id, true, false, "reply produced; completion not independently verified")
        val block = OrionMissionControl.evidence("ship the settings page")
        assertTrue(block.contains("Objective: ship the settings page"))
        assertTrue(block.contains("evidence: reply produced"))
        assertEquals("", OrionMissionControl.evidenceBlock("", emptyList(), emptyList()))
    }

    @Test fun retrieveBeforeActIsRecordedInOrder() {
        OrionEntityLoop.clearForTest()
        OrionEntityLoop.begin("chat")
        OrionEntityLoop.mark(OrionEntityLoop.Stage.RECALL)
        OrionEntityLoop.mark(OrionEntityLoop.Stage.ACT)
        assertTrue(OrionEntityLoop.end()!!.retrievedBeforeAct)
        OrionEntityLoop.begin("voice")
        OrionEntityLoop.mark(OrionEntityLoop.Stage.ACT)
        OrionEntityLoop.mark(OrionEntityLoop.Stage.RECALL)
        assertFalse(OrionEntityLoop.end()!!.retrievedBeforeAct)
        assertTrue(OrionEntityLoop.report().contains("retrieve-before-act: 1/2"))
    }
}
