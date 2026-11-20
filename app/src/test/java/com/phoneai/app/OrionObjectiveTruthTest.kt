package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionObjectiveTruthTest {
    private val goal = "generate a image of a police cat"
    private val fresh = 60_000L

    @Test fun greetingsAndStatusQuestionsDoNotResumeTheOldTask() {
        for (q in listOf("Hey bro", "What wrong with you", "How are you doing today", "How is everything at Ur end"))
            assertFalse(q, OrionObjectiveRelevance.related(goal, q, fresh))
    }

    @Test fun followUpsAndSharedSubjectDoResume() {
        assertTrue(OrionObjectiveRelevance.related(goal, "continue", fresh))
        assertTrue(OrionObjectiveRelevance.related(goal, "make the cat look more serious", fresh))
        assertTrue(OrionObjectiveRelevance.related(goal, "fix that", fresh))
    }

    @Test fun staleObjectivesNeverResume() {
        assertFalse(OrionObjectiveRelevance.related(goal, "continue", OrionObjectiveRelevance.STALE_MS + 1))
        assertFalse(OrionObjectiveRelevance.related("", "continue", fresh))
    }

    @Test fun onlyWorkRequestsBecomeTheStandingMission() {
        assertFalse(OrionObjectiveRelevance.isMissionWorthy("Hey bro"))
        assertFalse(OrionObjectiveRelevance.isMissionWorthy("How are you doing today"))
        assertTrue(OrionObjectiveRelevance.isMissionWorthy("open youtube and play afro beats"))
    }

    @Test fun unrelatedMessageSeesTheOldObjectiveOnlyAsBackground() {
        OrionMissionControl.clearForTest()
        val block = OrionMissionControl.evidenceBlock("", emptyList(), emptyList(), goal)
        assertTrue(block.contains("NOT the current request"))
        assertFalse(block.contains("Objective: $goal"))
        assertEquals("", OrionMissionControl.evidenceBlock("", emptyList(), emptyList()))
    }
}
