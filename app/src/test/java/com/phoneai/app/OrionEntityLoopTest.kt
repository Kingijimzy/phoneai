package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionEntityLoopTest {
    @Test fun turnRecordsOnlyStagesReached() {
        OrionEntityLoop.clearForTest()
        OrionEntityLoop.begin("voice")
        OrionEntityLoop.mark(OrionEntityLoop.Stage.ACT)
        val t = OrionEntityLoop.end()!!
        assertTrue(OrionEntityLoop.Stage.OBSERVE in t.reached)
        assertTrue(OrionEntityLoop.Stage.VERIFY in t.missing)
        assertTrue(OrionEntityLoop.report().contains("voice skips"))
    }

    @Test fun markWithoutTurnIsIgnored() {
        OrionEntityLoop.clearForTest()
        OrionEntityLoop.mark(OrionEntityLoop.Stage.ACT)
        assertEquals(null, OrionEntityLoop.end())
    }

    @Test fun turnContextReusedOncePerRequestWithinTtl() {
        val s = OrionTurnContext.Snapshot("open spotify", "ctx", 1_000L)
        assertTrue(OrionTurnContext.reusable(s, "open spotify", 1_000L + OrionTurnContext.TTL_MS))
        assertFalse(OrionTurnContext.reusable(s, "open spotify", 1_000L + OrionTurnContext.TTL_MS + 1))
        assertFalse(OrionTurnContext.reusable(s, "different request", 2_000L))
        assertFalse(OrionTurnContext.reusable(OrionTurnContext.Snapshot("a", "", 1_000L), "a", 1_500L))
    }

    @Test fun noSurfaceBuildsCentralContextDirectly() {
        val dir = java.io.File("src/main/java/com/phoneai/app")
        // The Mind page's "Show the live prompt" action is a diagnostic preview,
        // not a conversational surface or an active user turn. It intentionally
        // builds a fresh context for inspection and must not be treated as a turn bypass.
        val offenders = dir.listFiles { f -> f.extension == "kt" }!!
            .filter { it.name !in setOf("OrionTurnContext.kt", "OrionCentralBrain.kt", "OrionMindActivity.kt") }
            .filter { it.readText().contains("OrionCentralBrain.contextFor(") }.map { it.name }
        assertTrue("direct contextFor callers: $offenders", offenders.isEmpty())
    }

    /** Scope: checks the lexical shortlist the model sees when it searches for tools, not live model choice. */
    @Test fun toolShortlistRecallAtEight() {
        val cases = mapOf(
            "open app" to "open_app", "read screen" to "read_screen", "workspace write" to "workspace_write",
            "whatsapp compose" to "whatsapp_compose", "python exec" to "python_exec", "brain" to "brain"
        )
        var hits = 0
        for ((q, want) in cases) {
            val names = JSONObject(ToolRegistry.searchCapabilities(q, 8)).getJSONArray("results")
            if ((0 until names.length()).any { names.getJSONObject(it).getString("name") == want }) hits++
        }
        assertTrue("recall@8 = $hits/${cases.size}", hits * 10 >= cases.size * 7)
    }
}
