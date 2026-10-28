package com.phoneai.app

import com.phoneai.app.CapabilityRegistry.Row
import org.junit.Assert.*
import org.junit.Test

class CapabilityStatusTextTest {
    private fun row(tool: String, pack: String?, denied: Boolean = false, packOn: Boolean = true) =
        Row(tool, pack, denied, CapabilityRegistry.forBuiltInTool(tool, denied, packOn))

    private val titles = { id: String -> "Title of $id" }
    private val how = { pack: String -> "Settings for $pack" }

    @Test fun emptyWhenEverythingIsReady() {
        val text = CapabilityRegistry.statusText(listOf(row("shell", "workspace_shell"), row("time", null)), titles, how)
        assertEquals("", text)
    }

    @Test fun offPackNamesToolsAndWhereToSwitchItOn() {
        val text = CapabilityRegistry.statusText(
            listOf(row("shell", "workspace_shell", packOn = false), row("time", null)), titles, how
        )
        assertTrue(text.contains("Title of workspace_shell is OFF, so these are unavailable: shell"))
        assertTrue(text.contains("Settings for workspace_shell"))
        assertTrue(text.contains("The feature exists; it is not missing."))
    }

    @Test fun deniedToolsAreReportedSeparatelyFromOffPacks() {
        val text = CapabilityRegistry.statusText(
            listOf(row("shell", "workspace_shell", denied = true), row("gmail", "connected_services", packOn = false)),
            titles, how
        )
        assertTrue(text.contains("set these to DENY: shell"))
        assertTrue(text.contains("gmail"))
        assertFalse(text.contains("DENY: gmail"))
    }

    @Test fun listsAreTruncatedWithAnExplicitCount() {
        val many = (1..9).map { row("t$it", "workspace_shell", packOn = false) }
        val text = CapabilityRegistry.statusText(many, titles, how)
        assertTrue(text.contains("and 3 more"))
    }

    @Test fun everyUnusableToolAppearsAndNoUsableToolDoes() {
        val rows = listOf(
            row("ok_tool", null),
            row("off_tool", "workspace_shell", packOn = false),
            row("deny_tool", null, denied = true)
        )
        val text = CapabilityRegistry.statusText(rows, titles, how)
        assertTrue(text.contains("off_tool"))
        assertTrue(text.contains("deny_tool"))
        assertFalse(text.contains("ok_tool"))
    }
}
