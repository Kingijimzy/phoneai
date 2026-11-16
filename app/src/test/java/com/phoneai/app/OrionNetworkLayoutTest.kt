package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class OrionNetworkLayoutTest {
    private val order = OrionNodes.all.map { it.id }
    private val w = 1080f; private val h = 1100f; private val r = 66f; private val m = 24f

    @Test fun everyNodeStaysInsideTheStage() {
        val pos = OrionNodes.layout(order, OrionNodes.linked(listOf("reddit", "x")), w, h, r, m)
        assertEquals(17, pos.size)
        pos.values.forEach { (x, y) ->
            assertTrue(x >= r && x <= w - r); assertTrue(y >= r && y <= h - r)
        }
    }

    @Test fun nodesNeverOverlap() {
        listOf(emptyList(), listOf("reddit"), listOf("reddit", "x", "linkedin", "youtube", "facebook")).forEach { social ->
            val pos = OrionNodes.layout(order, OrionNodes.linked(social), w, h, r, m).values.toList()
            for (i in pos.indices) for (j in i + 1 until pos.size)
                assertTrue("overlap $i/$j with $social", hypot(pos[i].first - pos[j].first, pos[i].second - pos[j].second) > r * 1.5f)
        }
    }

    @Test fun linkedNodesSitCloserToTheOrbThanUnlinked() {
        val linked = OrionNodes.linked(listOf("reddit"))
        val pos = OrionNodes.layout(order, linked, w, h, r, m)
        fun d(id: String) = hypot(pos.getValue(id).first - w / 2, pos.getValue(id).second - h / 2)
        assertTrue(d("memory") < d("facebook"))
    }

    @Test fun orbClearsTheInnerRing() {
        val d = OrionNodes.orbDiameter(w, h, r, m, 20f)
        val pos = OrionNodes.layout(order, OrionNodes.linked(emptyList()), w, h, r, m)
        pos.filterKeys { it in OrionNodes.linked(emptyList()) }.values.forEach { (x, y) ->
            assertTrue(hypot(x - w / 2, y - h / 2) - r >= d / 2 - 1f)
        }
    }
}
