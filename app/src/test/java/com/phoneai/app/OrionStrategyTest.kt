package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionStrategyTest {
    private val wifi = JSONObject().put("network", "wifi")
    @Test fun measuredFailuresDemoteARoute() {
        val stats = mapOf("research_web" to Triple(0, 5, 0L), "live_web_search" to Triple(6, 0, 12000L))
        val r = OrionStrategy.rank(OrionStrategy.Work.RESEARCH, wifi, stats)
        assertEquals("live_web_search", r.first().tool)
    }
    @Test fun offlineRemovesNetworkRoutes() {
        val r = OrionStrategy.rank(OrionStrategy.Work.RESEARCH, JSONObject().put("network", "offline"), emptyMap())
        assertFalse(r.any { it.tool == "research_web" })
        assertTrue(r.any { it.tool == "ask_agent" })
    }
    @Test fun blockedRouteNamesNextBest() {
        val n = OrionStrategy.nextBest("research_web", JSONObject().put("network", "offline"))
        assertTrue(n!!.contains("ask_agent"))
    }
}
