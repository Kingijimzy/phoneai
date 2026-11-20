package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias D = OrionModelRegistry.Dim

class OrionModelRegistryTest {

    @Test fun foldIsRunningMeanAndBounded() {
        var s: OrionModelRegistry.Stat? = null
        listOf(1.0, 0.0, 1.0, 1.0).forEach { s = OrionModelRegistry.fold(s, it) }
        assertEquals(4, s!!.n); assertEquals(0.75, s!!.mean, 1e-9)
        repeat(500) { s = OrionModelRegistry.fold(s, 1.0) }
        assertTrue(s!!.n <= 200)
    }

    @Test fun coversEveryRequestedDimensionAndProbesScoreByCode() {
        assertEquals(8, D.values().size)
        assertEquals(1.0, OrionModelRegistry.scoreJson("{\"tool\":\"open_app\",\"args\":{\"name\":\"Spotify\",\"retries\":2}}"), 1e-9)
        assertEquals(0.0, OrionModelRegistry.scoreJson("sure, opening it"), 1e-9)
        val byDim = OrionModelRegistry.probes.associateBy { it.dim }
        assertEquals(1.0, byDim[D.REASONING]!!.score("17:15"), 1e-9)
        assertEquals(0.0, byDim[D.REASONING]!!.score("17:55"), 1e-9)
        assertEquals(1.0, byDim[D.LONG_CONTEXT]!!.score("7419"), 1e-9)
        assertEquals(1.0, byDim[D.CODING]!!.score("1..n"), 1e-9)
        assertTrue(byDim[D.LONG_CONTEXT]!!.prompt.length > 8_000)
    }

    @Test fun bestNeedsSamplesAndLatencyPrefersLowest() {
        val a = mapOf(D.CODING to OrionModelRegistry.Stat(5, 0.9), D.FIRST_TOKEN to OrionModelRegistry.Stat(5, 900.0))
        val b = mapOf(D.CODING to OrionModelRegistry.Stat(1, 1.0), D.FIRST_TOKEN to OrionModelRegistry.Stat(5, 300.0))
        val all = mapOf("A|m1" to a, "B|m2" to b)
        assertEquals("A|m1", OrionModelRegistry.best(all, D.CODING))
        assertEquals("B|m2", OrionModelRegistry.best(all, D.FIRST_TOKEN))
        assertNull(OrionModelRegistry.best(all, D.REASONING))
        assertNull(OrionModelRegistry.quality(emptyMap()))
    }

    @Test fun splitsProviderLabel() {
        assertEquals("NVIDIA" to "llama-3.1-70b", OrionModelRegistry.split("NVIDIA / llama-3.1-70b"))
        assertNull(OrionModelRegistry.split(""))
    }
}
