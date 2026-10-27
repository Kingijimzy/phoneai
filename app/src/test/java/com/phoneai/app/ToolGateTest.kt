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

    @Test fun nothingReportedWhenEverythingIsReady() {
        assertEquals("", run(on = setOf("workspace_shell", "orion_core")))
    }

    @Test fun offPackIsNamedWithItsFixAndSaidToExist() {
        val s = run(on = setOf("orion_core"))
        assertTrue(s.contains("Workspace shell is OFF"))
        assertTrue(s.contains("shell"))
        assertTrue(s.contains("Agent control > Packs > Workspace shell"))
        assertTrue(s.contains("The feature exists"))
    }

    @Test fun deniedToolIsReportedSeparatelyAndNotAsMissing() {
        val s = run(on = setOf("workspace_shell", "orion_core"), deny = setOf("open_app"))
        assertTrue(s.contains("DENY"))
        assertTrue(s.contains("open_app"))
        assertFalse(s.contains("OFF"))
    }

    @Test fun alwaysAllowedToolsAreNeverReportedBlocked() {
        val s = run(on = setOf("workspace_shell"), deny = setOf("ask_user"))
        assertFalse(s.contains("ask_user"))
    }

    @Test fun unmappedToolsAreSkippedNotInvented() {
        val s = ToolGate.summarize(listOf("mystery"), { ToolMode.ALLOW }, { null }, { false }, { it })
        assertEquals("", s)
    }

    @Test fun longPackListIsTruncated() {
        val many = (1..9).map { "t$it" }
        val map = many.associateWith { "workspace_shell" }
        val s = ToolGate.summarize(many, { ToolMode.ALLOW }, { map[it] }, { false }, { "Workspace shell" })
        assertTrue(s.contains("and 3 more"))
    }
}
