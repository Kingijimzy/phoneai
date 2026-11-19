package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionAdaptivePlanTest {
    private fun p(id: String, configured: Boolean = true, chat: Boolean = true, state: String = "HEALTHY", ok: Int = 3) =
        OrionAdaptivePlan.ProviderState(id, id, configured, chat, state, ok)
    private fun cap(tool: String, backend: String = "utilities", write: Boolean = true, calls: Int = 5, failures: Int = 0, consecutive: Int = 0) =
        OrionBrain.Capability(tool, backend, write, calls, failures, consecutive, "")

    @Test fun healthyProvidersAndToolsMeanFullPowerWithOrderedFailover() {
        val plan = OrionAdaptivePlan.plan(listOf(p("a", ok = 9), p("b", state = "UNTESTED", ok = 0)), listOf(cap("shell_run")))
        assertEquals(OrionAdaptivePlan.Mode.FULL, plan.mode); assertEquals("a", plan.primary); assertEquals(listOf("b"), plan.fallbacks)
    }

    @Test fun aDownProviderReducesAndIsRoutedAround() {
        val plan = OrionAdaptivePlan.plan(listOf(p("a", state = "DOWN"), p("b")), emptyList())
        assertEquals(OrionAdaptivePlan.Mode.REDUCED, plan.mode); assertEquals("b", plan.primary)
        assertTrue(plan.notes.any { it.contains("no failover") })
    }

    @Test fun nonChatModelsAreNotUsableForTools() {
        val plan = OrionAdaptivePlan.plan(listOf(p("a", chat = false)), emptyList())
        assertEquals(OrionAdaptivePlan.Mode.CONVERSATION, plan.mode); assertNull(plan.primary)
    }

    @Test fun nothingConfiguredIsOfflineAndSaysWhatToAdd() {
        val plan = OrionAdaptivePlan.plan(listOf(p("a", configured = false)), emptyList())
        assertEquals(OrionAdaptivePlan.Mode.OFFLINE, plan.mode)
        assertTrue(plan.notes.first().contains("Settings"))
    }

    @Test fun degradedToolGetsAHealthySiblingOrAnHonestNote() {
        val caps = listOf(cap("shell_run", consecutive = 3), cap("python_exec", calls = 8), cap("lonely", backend = "web", consecutive = 2))
        val plan = OrionAdaptivePlan.plan(listOf(p("a")), caps)
        assertEquals(OrionAdaptivePlan.Mode.REDUCED, plan.mode)
        assertEquals("python_exec", plan.substitutions["shell_run"])
        assertTrue(plan.notes.any { it.contains("lonely") })
        assertTrue(OrionAdaptivePlan.render(plan).contains("use instead python_exec for shell_run"))
    }
}
