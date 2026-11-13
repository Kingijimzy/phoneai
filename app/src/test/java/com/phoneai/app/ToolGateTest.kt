package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ToolGateTest {
    private val titles = mapOf("workspace_shell" to "Workspace shell", "orion_core" to "Orion core")
    private val packs = mapOf("shell" to "workspace_shell", "open_app" to "orion_core", "wait" to "orion_core", "ask_user" to "orion_core")

    private fun run(on: Set<String>, deny: Set<String> = emptySet(), tools: List<String> = packs.keys.toList()) = ToolGate.summarize(
        tools = tools,
        modeOf = { if (it in deny) ToolMode.DENY else ToolMode.ALLOW },
        packOf = { packs[it] },
        packOn = { it in on },
        titleOf = { titles[it] ?: it }
    )

    @Test fun localPackAndToolSettingsNeverReportRegisteredToolsUnavailable() {
        assertEquals("", run(on = emptySet(), deny = setOf("shell", "open_app")))
        assertEquals("", run(on = setOf("orion_core")))
    }

    @Test fun unmappedToolsAreSkippedNotInvented() {
        val s = ToolGate.summarize(listOf("mystery"), { ToolMode.ALLOW }, { null }, { false }, { it })
        assertEquals("", s)
    }

    @Test fun legacyPackAndPermissionStatusDoesNotCreateBlockerText() {
        val many = (1..20).map { "t$it" }
        val map = many.associateWith { "workspace_shell" }
        val s = ToolGate.summarize(many, { ToolMode.DENY }, { map[it] }, { false }, { "Workspace shell" })
        assertEquals("", s)
    }
}

class PreflightMessageTest {
    @Test fun packOffMessageNamesThePackAndTheFixAndSaysItExists() {
        val m = AgentPolicy.packOffMessage("workspace_shell")
        assertTrue(m.contains("workspace_shell"))
        assertTrue(m.contains("Agent control > Packs > Workspace shell"))
        assertTrue(m.contains("The feature exists"))
    }
}
