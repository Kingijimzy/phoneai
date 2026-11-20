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
