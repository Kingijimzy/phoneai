package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrionSpeechStreamerTest {
    private fun run(vararg parts: String): List<String> {
        val s = OrionSpeechStreamer { 1L }
        val out = ArrayList<String>()
        parts.forEach { out += s.feed(it) }
        out += s.flush()
        return out
    }

    @Test fun firstSentenceIsEmittedBeforeTheReplyEnds() {
        val s = OrionSpeechStreamer { 1L }
        assertTrue(s.feed("Let me look at that for you").isEmpty())
        val first = s.feed(". Then I will check the logs and tell you what I find")
        assertEquals(listOf("Let me look at that for you."), first)
    }

    @Test fun codeFencesAreNeverSpoken() {
        val out = run("Here is the fix. ", "```kotlin\nval x = 1\n", "```\n", "That should do it.")
        assertTrue(out.none { it.contains("val x") || it.contains("```") })
        assertTrue(out.any { it.contains("That should do it") })
    }

    @Test fun decimalsDoNotSplitSentences() {
        val out = run("The model supports 3.5 million tokens of context in total. Next point is here.")
        assertEquals("The model supports 3.5 million tokens of context in total.", out.first())
    }

    @Test fun mutedStreamerSaysNothing() {
        val s = OrionSpeechStreamer { 1L }
        s.mute()
        assertTrue(s.feed("Anything at all. More text.").isEmpty() && s.flush().isEmpty())
    }

    @Test fun bargeInNeedsSustainedLoudSpeechAboveLeakage() {
        val p = OrionBargeInPolicy()
        repeat(15) { assertFalse(p.onFrame(0.01f)) }
        repeat(10) { assertFalse(p.onFrame(0.012f)) }
        var fired = false
        repeat(20) { if (p.onFrame(0.2f)) fired = true }
        assertTrue(fired)
    }

    @Test fun shortNoiseBurstDoesNotInterrupt() {
        val p = OrionBargeInPolicy()
        repeat(15) { p.onFrame(0.01f) }
        var fired = false
        repeat(5) { if (p.onFrame(0.3f)) fired = true }
        repeat(30) { if (p.onFrame(0.01f)) fired = true }
        assertFalse(fired)
    }

    @Test fun streamedToolCallFragmentsReassemble() {
        val acc = OrionSseToolAccumulator()
        acc.feed(JSONArray("""[{"index":0,"id":"c1","function":{"name":"web_se","arguments":""}}]"""))
        acc.feed(JSONArray("""[{"index":0,"function":{"name":"arch","arguments":"{\"q\":"}}]"""))
        acc.feed(JSONArray("""[{"index":0,"function":{"arguments":"\"hi\"}"}}]"""))
        val j = acc.toJson().getJSONObject(0)
        assertEquals("c1", j.getString("id"))
        assertEquals("web_search", j.getJSONObject("function").getString("name"))
        assertEquals("""{"q":"hi"}""", j.getJSONObject("function").getString("arguments"))
        assertTrue(acc.hasCalls())
    }
}
