package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionLiveStreamTest {
    private fun step(id: Long, title: String, status: OrionRunLog.Status, out: String = "", detail: String = "") =
        OrionRunLog.Step(id, "shell", title, detail, null, 0L, 0L, status, out)

    @Test fun nowLineShowsNewestOutputLine() {
        val s = step(1, "Running a command", OrionRunLog.Status.RUNNING, out = "\$ cd /workspace\nframe 12/30\nframe 13/30\n", detail = "cd /workspace")
        assertEquals("frame 13/30", OrionStreamText.nowLine(s))
    }
    @Test fun nowLineFallsBackToDetailThenTitle() {
        assertEquals("cd /workspace", OrionStreamText.nowLine(step(1, "Running a command", OrionRunLog.Status.RUNNING, detail = "cd /workspace")))
        assertEquals("Running a command", OrionStreamText.nowLine(step(1, "Running a command", OrionRunLog.Status.RUNNING)))
    }
    @Test fun trailKeepsLastTwoFinishedAndMarksFailures() {
        val t = OrionStreamText.trail(listOf(step(1, "a", OrionRunLog.Status.DONE), step(2, "b", OrionRunLog.Status.FAILED), step(3, "c", OrionRunLog.Status.DONE), step(4, "d", OrionRunLog.Status.RUNNING)))
        assertEquals(listOf("retrying after: b", "c"), t)
        assertTrue(t.none { it.contains("d") })
    }
}

class OrionRunSheetTest {
    private fun step(args: String = "", detail: String = "", title: String = "Running a command", start: Long = 0L, end: Long = 0L) =
        OrionRunLog.Step(1, "shell", title, detail, null, start, end, OrionRunLog.Status.DONE, "see https://example.com/page.", args)

    @Test fun commandComesFromArgsThenDetailThenTitle() {
        assertEquals("ls -la", OrionRunSheetText.commandOf(step(args = "{\"command\":\"ls -la\"}")))
        assertEquals("cd /w", OrionRunSheetText.commandOf(step(detail = "cd /w")))
        assertEquals("Running a command", OrionRunSheetText.commandOf(step()))
    }
    @Test fun headlineAndLinks() {
        assertEquals("No steps yet", OrionRunSheetText.headline(emptyList()))
        assertEquals("Ran 2 steps", OrionRunSheetText.headline(listOf(step(), step())))
        assertTrue(OrionRunSheetText.links(step()).any { it.startsWith("https://example.com/page") })
    }
    @Test fun durationBarsAreRelativeToTheSlowestStep() {
        val a = step(start = 0L, end = 10_000L); val b = step(start = 0L, end = 5_000L)
        assertEquals(1f, OrionRunSheetText.share(a, listOf(a, b), 0L), 0.001f)
        assertEquals(0.5f, OrionRunSheetText.share(b, listOf(a, b), 0L), 0.001f)
    }
}
