package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionJobLedgerTest {
    private val job = OrionJobLedger.start("Build the analyser", listOf("download video", "extract frames", "test it"))
    @Test fun stepNeedsEvidenceToBeVerified() {
        val (j, msg) = OrionJobLedger.mark(job, 0, OrionJobLedger.State.VERIFIED, "")
        assertTrue(msg.startsWith("error")); assertEquals(3, j.open.size)
    }
    @Test fun jobFinishesOnlyWhenEveryStepIsResolved() {
        var j = job
        j = OrionJobLedger.mark(j, 0, OrionJobLedger.State.VERIFIED, "/workspace/v.mp4 42 MB").first
        j = OrionJobLedger.mark(j, 1, OrionJobLedger.State.BLOCKED, "ffmpeg missing in runtime").first
        assertFalse(j.finished)
        j = OrionJobLedger.mark(j, 2, OrionJobLedger.State.VERIFIED, "tests 4/4 passed").first
        assertTrue(j.finished); assertTrue(OrionJobLedger.format(j).contains("report"))
    }
    @Test fun roundTripsThroughJson() {
        val back = OrionJobLedger.fromJson(OrionJobLedger.toJson(job))!!
        assertEquals(job.objective, back.objective); assertEquals(3, back.steps.size)
    }
    @Test fun badIndexIsRejected() = assertTrue(OrionJobLedger.mark(job, 9, OrionJobLedger.State.VERIFIED, "something real").second.startsWith("error"))
    @Test fun openJobReportsWhatIsUnverified() {
        val text = OrionJobLedger.format(job)
        assertTrue(text.contains("3 step(s) still open")); assertTrue(text.contains("do not report as done"))
    }
}
