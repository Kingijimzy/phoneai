package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionBrainTest {
    private val day = 86_400_000L
    private fun step(at: Long, tool: String, ok: Boolean, cat: String = "", prev: String = "", prevCat: String = "") =
        OrionBrain.Step(at, "fix build", tool, "why", ok, cat, prev, prevCat)

    @Test fun statusIsMeasuredNotClaimed() {
        assertEquals(OrionBrain.Status.READY, OrionBrain.classify(0, 0, 0))
        assertEquals(OrionBrain.Status.PROVEN, OrionBrain.classify(5, 1, 0))
        assertEquals(OrionBrain.Status.DEGRADED, OrionBrain.classify(5, 3, 2))
        // a tool that only ever failed once is not proven
        assertEquals(OrionBrain.Status.READY, OrionBrain.classify(1, 1, 1))
    }

    @Test fun reasonsComeFromWhatActuallyLedHere() {
        assertTrue(OrionBrain.whyFor("fix build", null, true, "", "shell").startsWith("first step toward: fix build"))
        assertTrue(OrionBrain.whyFor("g", "browser_open", false, "target_not_found", "web_fetch").contains("recovering: browser_open failed (target_not_found)"))
        assertEquals("continuing with shell", OrionBrain.whyFor("g", "shell", true, "", "shell"))
        assertEquals("next step after read_screen succeeded", OrionBrain.whyFor("g", "read_screen", true, "", "click"))
    }

    @Test fun repeatedRecoveriesBecomeALesson() {
        val now = 100 * day
        val steps = listOf(
            step(now - 3 * day, "web_fetch", true, prev = "browser_open", prevCat = "network_or_timeout"),
            step(now - 2 * day, "web_fetch", true, prev = "browser_open", prevCat = "network_or_timeout"),
            step(now - day, "shell", true)
        )
        val lessons = OrionBrain.consolidate(steps, now).lessons
        assertEquals(1, lessons.size)
        assertTrue(lessons[0].contains("browser_open fails with network_or_timeout"))
        assertTrue(lessons[0].contains("web_fetch has worked 2 times"))
    }

    @Test fun aToolThatNeverWorkedIsFlaggedButOneRecoveryIsNotALesson() {
        val now = 100 * day
        val steps = listOf(
            step(now - 3000, "mesh", false, "network_or_timeout"), step(now - 2000, "mesh", false, "network_or_timeout"), step(now - 1000, "mesh", false, "rate_limited"),
            step(now - 500, "web_fetch", true, prev = "x", prevCat = "y")
        )
        val lessons = OrionBrain.consolidate(steps, now).lessons
        assertEquals(1, lessons.size)
        assertTrue(lessons[0].startsWith("mesh failed 3 times"))
    }

    @Test fun dreamingForgetsOldStepsOnly() {
        val now = 100 * day
        val kept = OrionBrain.consolidate(listOf(step(now - 20 * day, "a", true), step(now - day, "b", true)), now).keep
        assertEquals(listOf("b"), kept.map { it.tool })
    }

    @Test fun summaryNamesDegradedToolsAndTheTruthRule() {
        val caps = listOf(
            OrionBrain.Capability("shell", "shell", true, 10, 1, 0, ""),
            OrionBrain.Capability("mesh", "utilities", true, 4, 4, 4, "timeout"),
            OrionBrain.Capability("recall", "memory", false, 0, 0, 0, "")
        )
        val s = OrionBrain.summary(caps)
        assertTrue(s.contains("3 tools routed, 1 proven"))
        assertTrue(s.contains("mesh (4x: timeout)"))
        assertTrue(s.contains("Only claim a capability as working if it is proven"))
    }

    @Test fun brainToolIsRoutedAndReadOnly() {
        assertEquals(ToolAccess.READ, ToolRegistry.access("brain"))
        assertEquals("brain", OrionCapabilityAdapters.route("brain")?.tool)
    }
}
