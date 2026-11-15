package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionNodesTest {
    @Test fun toolsMapToTheirNodes() {
        assertEquals("gmail", OrionNodes.nodeFor("gmail_list_messages"))
        assertEquals("shell", OrionNodes.nodeFor("execute_system_op"))
        assertEquals("web", OrionNodes.nodeFor("browser_click"))
        assertEquals("memory", OrionNodes.nodeFor("recall"))
        assertEquals("files", OrionNodes.nodeFor("workspace_write"))
        assertNull(OrionNodes.nodeFor("wait"))
    }

    @Test fun socialToolFollowsThePlatform() {
        assertEquals("twitter", OrionNodes.nodeFor("social", JSONObject().put("platform", "x")))
        assertEquals("facebook", OrionNodes.nodeFor("social", JSONObject().put("platform", "Instagram")))
        assertEquals("reddit", OrionNodes.nodeFor("social", JSONObject().put("platform", "reddit")))
    }

    @Test fun linkedCountsRealConnections() {
        val base = OrionNodes.linked(emptyList())
        assertFalse("reddit" in base)
        val withSocial = OrionNodes.linked(listOf("reddit", "x"))
        assertTrue("reddit" in withSocial && "twitter" in withSocial)
        assertEquals(17, OrionNodes.all.size)
        assertTrue(OrionNodes.headline(withSocial).endsWith("of 17 links active"))
    }

    @Test fun ringStartsAtTopAndHotWindowExpires() {
        val (x, y) = OrionNodes.ringPoint(0, 17, 100f, 100f, 50f, 50f)
        assertEquals(100f, x, 0.01f); assertEquals(50f, y, 0.01f)
        val p = OrionNodes.Pulse(1, 1_000L)
        assertTrue(OrionNodes.isHot(p, 2_000L))
        assertFalse(OrionNodes.isHot(p, 20_000L))
        assertFalse(OrionNodes.isHot(null, 0L))
    }
}
