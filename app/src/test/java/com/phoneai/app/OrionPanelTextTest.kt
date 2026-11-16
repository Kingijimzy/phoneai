package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionPanelTextTest {
    @Test fun codeNeverShowsInThePanel() {
        val out = OrionPanelText.clean("Done.\n```kotlin\nfun main() {}\nprintln(1)\n```\n**All** set")
        assertFalse(out.contains("fun main")); assertFalse(out.contains("```")); assertFalse(out.contains("**"))
        assertTrue(out.contains("▣ Code")); assertTrue(out.contains("All set"))
    }

    @Test fun unfinishedFenceWhileStreamingIsHidden() {
        assertFalse(OrionPanelText.clean("Here:\n```python\nprint('x'").contains("print"))
    }

    @Test fun statusLineFollowsTheMode() {
        assertEquals("Speaking. Talk to interrupt", OrionPanelText.statusLine(OrbView.Mode.SPEAKING, false, null))
        assertEquals("Running a command…", OrionPanelText.statusLine(OrbView.Mode.ACTING, true, "Running a command"))
        assertEquals("Tap the orb to talk", OrionPanelText.statusLine(OrbView.Mode.IDLE, false, null))
    }

    @Test fun taskStripWording() {
        assertEquals("3 of 3 steps", OrionPanelText.stepsLabel(3, 3))
        assertEquals("1 of 1 step", OrionPanelText.stepsLabel(1, 1))
        assertEquals("COMPLETED", OrionPanelText.chip(3, 3, 0, false))
        assertEquals("PARTIAL", OrionPanelText.chip(3, 3, 1, false))
        assertEquals("RUNNING", OrionPanelText.chip(1, 3, 0, true))
    }

    @Test fun runBoundaryOnlyCountsTheCurrentRun() {
        OrionRunLog.clear()
        val old = OrionRunLog.begin("recall", null); OrionRunLog.finish(old, "ok", false)
        OrionRunLog.beginRun()
        val a = OrionRunLog.begin("shell", null); OrionRunLog.finish(a, "ok", false)
        val b = OrionRunLog.begin("web_search", null)
        val run = OrionRunLog.currentRun()
        assertEquals(2, run.size)
        assertEquals(Triple(1, 2, 0), OrionRunLog.progress(run))
        OrionRunLog.finish(b, "x", true)
    }
}
