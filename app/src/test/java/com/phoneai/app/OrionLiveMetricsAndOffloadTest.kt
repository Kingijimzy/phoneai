package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionLiveMetricsAndOffloadTest {
    @Test fun metricsAreMeasuredNotInvented() {
        OrionLiveMetrics.begin(1_000)
        OrionLiveMetrics.onDelta(400, "nvidia/deepseek-v4-pro", 1_600)
        OrionLiveMetrics.onDelta(400, "", 3_600)
        OrionLiveMetrics.onTools(3, 1)
        OrionLiveMetrics.end("nvidia/deepseek-v4-pro", 4_000)
        val s = OrionLiveMetrics.state
        assertEquals(3_000L, OrionLiveMetrics.elapsedMs(s, 99_000))
        assertEquals(600L, OrionLiveMetrics.firstWordMs(s))
        assertEquals(100, OrionLiveMetrics.tokensPerSecond(s, 99_000)) // 800 chars = 200 tokens over 2.0 s
        val chips = OrionLiveMetrics.chips(s, 99_000)
        assertTrue(chips.contains("3 tools") && chips.contains("1 failed") && chips.contains("deepseek-v4-pro"))
    }

    @Test fun longCodeMovesToWorkspaceShortCodeStays() {
        val long = (1..12).joinToString("\n") { "val x$it = $it" }
        val text = "Here:\n```kotlin\n$long\n```\nand `short`:\n```py\nprint(1)\n```\nDone."
        val saved = HashMap<String, String>()
        val out = OrionCodeOffload.offload(text, "t", { p, c -> saved[p] = c; true })
        assertEquals(1, saved.size)
        assertTrue(saved.keys.first().endsWith(".kt"))
        assertFalse(out.contains("val x7"))
        assertTrue(out.contains("Code saved to workspace") && out.contains("12 lines"))
        assertTrue(out.contains("print(1)"))
    }

    @Test fun failedSaveKeepsTheCodeInChat() {
        val long = (1..10).joinToString("\n") { "line $it" }
        val out = OrionCodeOffload.offload("```\n$long\n```", "t", { _, _ -> false })
        assertTrue(out.contains("line 5"))
    }
}
