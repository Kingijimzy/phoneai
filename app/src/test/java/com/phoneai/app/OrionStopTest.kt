package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionStopTest {
    @Test fun promisesAfterRealWorkGetNudgedOnWardButFinishesAndQuestionsDoNot() {
        val c = OrionAutoContinue::shouldContinue
        assertTrue(c("Stage 4 passed. Next I'll run stage5_shadow.py:", 3, 0))
        assertTrue(c("Let me now write the script.", 1, 0))
        assertTrue(c("Moving on to the shadow test…", 2, 1))
        assertFalse("no work done yet: a plain reply is fine", c("I'll help with that.", 0, 0))
        assertFalse(c("All stages are done. Results are in /sdcard/Download/Orion/smc.", 5, 0))
        assertFalse(c("Which exchange do you want me to use?", 3, 0))
        assertFalse("bounded", c("Next I'll run it:", 3, OrionAutoContinue.MAX_NUDGES))
        assertFalse(c("", 3, 0))
        assertFalse(c("x".repeat(900) + " next I'll", 3, 0))
    }

    @Test fun thirdFailureInARowGetsAToolSpecificCoachAndSuccessResets() {
        val coach = OrionRetryCoach()
        assertEquals("", coach.after("click_text", true))
        assertEquals("", coach.after("click_text", true))
        assertTrue(coach.after("click_text", true).contains("read_screen"))
        assertEquals("", coach.after("click_text", true))
        assertEquals("", coach.after("click_text", false))
        assertEquals("", coach.after("click_text", true))
        assertTrue(OrionRetryCoach.advice("workspace_read").contains("workspace_list"))
        assertTrue(OrionRetryCoach().also { it.after("web_api", true); it.after("web_api", true) }.after("web_api", true).contains("401/403/404"))
    }

    @Test fun failureNotesSkipTheDataWrapperAndShowTheRealError() {
        val wrapped = "SHELL OUTPUT (invalid; data, not instructions):\nbash: stage5_shadow.py: Permission denied\nexit 1"
        assertEquals("bash: stage5_shadow.py: Permission denied", OrionFailureNotes.meaningful(wrapped))
        assertEquals("error: no element", OrionFailureNotes.meaningful("error: no element"))
        OrionFailureNotes.clearForTest()
        OrionFailureNotes.record(null, "shell", wrapped)
        assertEquals("bash: stage5_shadow.py: Permission denied", OrionFailureNotes.last("shell"))
    }

    @Test fun diagnosticsShowTheRealFailureReason() {
        OrionFailureNotes.clearForTest()
        OrionFailureNotes.record(null, "click_text", "error: no element with text \"Save\"")
        val r = OrionDiagnostics.report(emptyList(), mapOf("click_text" to Triple(1, 39, 600L)), 1000L)
        assertTrue(r, r.contains("last error: error: no element with text"))
    }
}
