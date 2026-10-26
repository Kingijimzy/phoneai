package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ExperiencePolicyTest {
    private val now = 1_000_000_000_000L
    private fun fail(task: String, tool: String, cat: String, at: Long = now) =
        Experience(ExperiencePolicy.gist(task), tool, "failure", cat, ExperiencePolicy.tipFor(tool, cat), 1, at)

    @Test fun repeatedFailuresMergeAndReinforce() {
        var l = emptyList<Experience>()
        l = ExperiencePolicy.merge(l, fail("send whatsapp message to John", "click", "target_not_found"))
        l = ExperiencePolicy.merge(l, fail("send a whatsapp message to John now", "click", "target_not_found"))
        assertEquals(1, l.size); assertEquals(2, l[0].count)
    }

    @Test fun laterSuccessRetiresOldFailure() {
        var l = ExperiencePolicy.merge(emptyList(), fail("open whatsapp chat John", "click", "target_not_found"))
        l = ExperiencePolicy.merge(l, Experience(ExperiencePolicy.gist("open whatsapp chat John"), "open_app", "worked", "", "", 1, now, "open_app>click"))
        assertTrue(l.none { it.kind == "failure" })
        assertEquals(1, l.size)
    }

    @Test fun rankingKeepsRelatedAndDropsUnrelated() {
        val l = listOf(fail("send whatsapp message John", "click", "target_not_found"), fail("play music youtube", "open_app", "network_or_timeout"))
        val r = ExperiencePolicy.rank(l, "message John on whatsapp", now, 5)
        assertEquals(1, r.size); assertEquals("click", r[0].tool)
    }

    @Test fun staleLessonsRankBelowFresh() {
        val old = fail("send whatsapp message John", "a", "other_failure", now - 400L * 86_400_000L)
        val fresh = fail("send whatsapp message John", "b", "other_failure", now)
        assertEquals("b", ExperiencePolicy.rank(listOf(old, fresh), "send whatsapp message John", now, 2)[0].tool)
    }

    @Test fun blockShowsAgeAndCount() {
        val b = ExperiencePolicy.block(listOf(fail("x y z", "click", "target_not_found", now - 2 * 86_400_000L)), now)
        assertTrue(b.contains("AVOID") && b.contains("2d ago"))
    }
    @Test fun routineLearningRequiresCleanSuccessfulEvidence() {
        assertFalse(ExperiencePolicy.hasCleanSuccessEvidence(emptyList(), 0))
        assertTrue(ExperiencePolicy.hasCleanSuccessEvidence(listOf(true, true), 0))
        assertFalse(ExperiencePolicy.hasCleanSuccessEvidence(listOf(true, false), 0))
        assertFalse(ExperiencePolicy.hasCleanSuccessEvidence(listOf(true), 1))
    }

}
