package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionLearningScaleTest {
    private val now = 1_800_000_000_000L
    private fun fail(i: Int) = Experience("task$i tool$i", "tool$i", "failure", "x", "tip", 1, now)

    @Test fun capIsNoLongerSixtyAndValueNotAgeDecidesWhatIsKept() {
        assertEquals(400, ExperiencePolicy.MAX)
        val old = Experience("send whatsapp message", "whatsapp_send", "correction", "", "always confirm the contact first", 3, now - 100L * 86_400_000L)
        val list = (1..400).map { fail(it) } + old
        val kept = ExperiencePolicy.prune(list, now)
        assertEquals(400, kept.size)
        assertTrue("an old repeated correction outranks a fresh one-off failure", kept.contains(old))
    }

    @Test fun listsUnderTheCapAreUntouched() {
        val list = (1..120).map { fail(it) }
        assertEquals(120, ExperiencePolicy.prune(list, now).size)
    }

    @Test fun gistKeepsMoreThanEightWords() {
        val g = ExperiencePolicy.gist("compare competitor pricing sheet build verify upload workspace report summary churn analysis dashboard export").split(" ")
        assertTrue("kept ${g.size}", g.size in 9..14)
    }

    @Test fun shortRequestRecallsALongerStoredLessonAcrossWordForms() {
        val stored = ExperiencePolicy.gist("sending whatsapp messages benedicta contact resolve confirm verify delivered")
        assertTrue(ExperiencePolicy.recallSimilarity(stored, "send whatsapp message") > 0.5)
        assertTrue(ExperiencePolicy.recallSimilarity(stored, "what is the weather in lagos") < 0.12)
    }

    @Test fun rankingUsesTheBroaderRecall() {
        val e = Experience(ExperiencePolicy.gist("sending whatsapp messages benedicta contact resolve confirm verify delivered"), "whatsapp_send", "failure", "target_not_found", "tip", 1, now)
        val r = ExperiencePolicy.rank(listOf(e), "send whatsapp message", now, 5)
        assertEquals(1, r.size)
    }
}
