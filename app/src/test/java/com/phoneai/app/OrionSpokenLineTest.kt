package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionSpokenLineTest {
    @Test fun parsesSayAndMood() {
        val l = OrionSpokenLine.parse("```json\n{\"say\":\"Backup's running, nothing lost.\",\"mood\":\"calm\"}\n```")!!
        assertEquals("Backup's running, nothing lost.", l.text)
        assertEquals(OrionSpokenLine.Mood.CALM, l.mood)
    }

    @Test fun unknownMoodIsNeutralAndBrokenJsonIsRejected() {
        assertEquals(OrionSpokenLine.Mood.NEUTRAL, OrionSpokenLine.parse("{\"say\":\"Hi.\",\"mood\":\"sparkly\"}")!!.mood)
        assertNull(OrionSpokenLine.parse("I would say hello"))
        assertNull(OrionSpokenLine.parse("{\"mood\":\"calm\"}"))
    }

    @Test fun emptySayMeansStaySilent() {
        assertEquals("", OrionSpokenLine.parse("{\"say\":\"\",\"mood\":\"neutral\"}")!!.text)
    }

    @Test fun cleanRemovesLinksMarkupEmojiAndSymbols() {
        assertEquals("Done, see the report now. It is at 80 percent and fine.",
            OrionSpokenLine.clean("**Done** \u2014 see the report \uD83D\uDE00 https://x.io/a now. It is at 80% and fine."))
        assertFalse(OrionSpokenLine.clean("Check `code` now").contains("`"))
    }

    @Test fun cleanKeepsAtMostThreeSentences() {
        val t = OrionSpokenLine.clean("One. Two. Three. Four. Five.")
        assertEquals("One. Two. Three.", t)
    }

    @Test fun planPacesByMoodAndLeavesNoPauseAfterLastSentence() {
        val calm = OrionSpokenLine.plan(OrionSpokenLine.Line("Slow down a moment. I have it.", OrionSpokenLine.Mood.CALM, "t"))
        val urgent = OrionSpokenLine.plan(OrionSpokenLine.Line("Slow down a moment. I have it.", OrionSpokenLine.Mood.URGENT, "t"))
        assertEquals(2, calm.size)
        assertTrue(calm[0].speed < urgent[0].speed)
        assertTrue(calm[0].pauseAfterMs > urgent[0].pauseAfterMs)
        assertEquals(0, calm.last().pauseAfterMs)
    }

    @Test fun questionsAreSlightlySlowerAndPausedLonger() {
        val c = OrionSpokenLine.plan(OrionSpokenLine.Line("Should I switch over now? Say the word.", OrionSpokenLine.Mood.NEUTRAL, "t"))
        assertTrue(c[0].speed < 1f)
        assertTrue(c[0].pauseAfterMs > OrionSpokenLine.Mood.NEUTRAL.pauseMs)
    }

    @Test fun speedIsClampedToEngineLimits() {
        val c = OrionSpokenLine.plan(OrionSpokenLine.Line("Quick one.", OrionSpokenLine.Mood.URGENT, "t"), 1.25f)
        assertTrue(c[0].speed <= 1.25f)
    }

    @Test fun shortPlainRepliesSkipTheComposerButCodeAndLongTextDoNot() {
        assertFalse(OrionSpokenLine.needsComposing("Sure, give me a second."))
        assertTrue(OrionSpokenLine.needsComposing("Here is the fix:\n- step one\n- step two"))
        assertTrue(OrionSpokenLine.needsComposing("See https://example.com"))
        assertTrue(OrionSpokenLine.needsComposing("x ".repeat(100)))
    }

    @Test fun spokenComposerSupportsTypedChatWithoutARequiredLiveCall() {
        assertTrue(OrionSpokenLine.SYSTEM.contains("ordinary typed chat"))
        assertTrue(OrionSpokenLine.SYSTEM.contains("no live call or microphone is required"))
        assertTrue(OrionSpokenLine.SYSTEM.contains("visible chat"))
        assertFalse(OrionSpokenLine.SYSTEM.contains("only for live voice calls"))
    }
}
