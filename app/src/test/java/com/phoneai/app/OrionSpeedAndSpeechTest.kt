package com.phoneai.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OrionSpeedAndSpeechTest {
    @Before fun reset() = OrionSpeedTrace.clearForTest()

    @Test fun traceMeasuresEachStageOnce() {
        OrionSpeedTrace.begin(1_000)
        OrionSpeedTrace.mark("context", 1_300); OrionSpeedTrace.mark("context", 1_900)
        OrionSpeedTrace.mark("token", 2_000); OrionSpeedTrace.mark("audio", 2_400)
        OrionSpeedTrace.end("nvidia/deepseek", 5_000)
        val t = OrionSpeedTrace.turns().single()
        assertEquals(300L, t.contextMs); assertEquals(1_000L, t.firstTokenMs); assertEquals(1_400L, t.firstAudioMs); assertEquals(4_000L, t.totalMs)
        assertEquals("deepseek", t.model)
    }

    @Test fun missingStageIsNotCountedAsZero() {
        OrionSpeedTrace.begin(1_000); OrionSpeedTrace.end("m", 3_000)
        assertEquals(-1L, OrionSpeedTrace.turns().single().firstAudioMs)
        assertTrue(OrionSpeedTrace.report().contains("first sound: median n/a"))
    }

    @Test fun percentilesAreReal() {
        assertEquals(30L, OrionSpeedTrace.percentile(listOf(10, 20, 30, 40, 50), 0.5))
        assertEquals(40L, OrionSpeedTrace.percentile(listOf(10, 20, 30, 40, 50), 0.9))
        assertEquals(-1L, OrionSpeedTrace.percentile(emptyList(), 0.5))
    }

    @Test fun longRepliesStopBeingSpokenAfterTheCapButChatKeepsThem() {
        val s = OrionSpeechStreamer(maxSpokenChars = 120)
        val reply = (1..12).joinToString(" ") { "This is sentence number $it of a long answer." }
        val spoken = s.feed(reply) + s.flush()
        assertTrue(spoken.isNotEmpty())
        assertTrue(s.capped)
        assertTrue("spoke ${spoken.sumOf { it.length }} chars", spoken.sumOf { it.length } < 220)
        assertTrue(s.feed("More text. And more text here.").isEmpty())
    }

    @Test fun shortRepliesAreSpokenInFull() {
        val s = OrionSpeechStreamer()
        val spoken = s.feed("Done. The file is saved in your workspace.") + s.flush()
        assertEquals("Done. The file is saved in your workspace.", spoken.joinToString(" "))
        assertFalse(s.capped)
    }

    @Test fun selfFactsCarryTheBuildAndTheMeasuredValues() {
        val text = OrionSelfFacts.render("1.0 (code 7)", 64_000, 60, 360, listOf("Groq"), listOf("Gemini"), 3, "Speed: not measured yet in this process.")
        assertTrue(text.contains("installed build 1.0 (code 7)"))
        assertTrue(text.contains("64000 tokens")); assertTrue(text.contains("capped at 60")); assertTrue(text.contains("at most 360 characters"))
        assertTrue(text.contains("Providers that stream: Groq")); assertTrue(text.contains("read whole (speech starts after the reply arrives): Gemini"))
        assertTrue(text.contains("unverified"))
    }
}
