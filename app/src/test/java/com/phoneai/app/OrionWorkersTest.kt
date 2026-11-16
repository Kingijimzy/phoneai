package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionWorkersTest {
    @Test fun workerToolsAreRegisteredAndRoutedThroughOneBackend() {
        listOf("spawn_worker", "worker_list", "worker_result", "worker_cancel", "platform_selftest").forEach {
            assertTrue(it, ToolRegistry.find(it) != null)
            assertEquals(it, OrionCapabilityAdapters.Backend.SHELL, OrionCapabilityAdapters.route(it)?.backend)
            assertEquals(it, "workspace_shell", AgentPolicy.packForTool(it))
        }
    }

    @Test fun everyRegisteredToolIsRouted() {
        assertEquals(emptyList<String>(), OrionSelfTest.unrouted())
    }

    @Test fun workerPromptIsAutonomousAndCarriesTheGoal() {
        val p = OrionWorkers.prompt("site watch", "watch example.com and report changes")
        assertTrue(p.contains("watch example.com")); assertTrue(p.contains("Do not ask questions"))
    }

    @Test fun selfTestSummaryCountsPasses() {
        val s = OrionSelfTest.summary(listOf(OrionSelfTest.Probe("a", true, 5, "ok"), OrionSelfTest.Probe("b", false, 9, "bad")))
        assertTrue(s.startsWith("Platform self-test: 1/2 passed")); assertTrue(s.contains("FAIL b"))
        assertFalse(s.contains("PASS b"))
    }
}
