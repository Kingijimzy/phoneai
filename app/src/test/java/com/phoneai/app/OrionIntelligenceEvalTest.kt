package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automated coverage for the intelligence reset eval scenarios (Rule 10).
 * Device ticks still required for full acceptance; these prove pure logic.
 */
class OrionIntelligenceEvalTest {

    @Test fun scenario2_openMusicRejectsWeb() {
        val v = OrionToolOutcomeVerifier.verify("open music app", "play_media", "opened YouTube results for music")
        assertFalse(v.accept)
    }

    @Test fun scenario2_openMusicAcceptsNative() {
        assertTrue(OrionToolOutcomeVerifier.verify("open music app", "play_media", "opened Spotify app").accept)
    }

    @Test fun scenario4_continueWithHistory() {
        val r = OrionContextualIntentInterpreter.resolve(
            "continue",
            listOf("user" to "Build a calculator"),
            "Build a calculator"
        )
        assertEquals(OrionContextualIntentInterpreter.Kind.CONTINUE_OBJECTIVE, r.kind)
        assertFalse(r.needsClarification)
    }

    @Test fun scenario5_continueWithoutHistory() {
        val r = OrionContextualIntentInterpreter.resolve("continue", emptyList(), "")
        assertEquals(OrionContextualIntentInterpreter.Kind.AMBIGUOUS_REFERENCE, r.kind)
        assertTrue(r.needsClarification)
    }

    @Test fun scenario6_fixThatIsCorrection() {
        val r = OrionContextualIntentInterpreter.resolve(
            "fix that",
            listOf("user" to "Integrate the browser"),
            "Integrate the browser"
        )
        assertTrue(
            r.kind == OrionContextualIntentInterpreter.Kind.CORRECT_PREVIOUS ||
                r.kind == OrionContextualIntentInterpreter.Kind.CONTINUE_OBJECTIVE
        )
    }

    @Test fun scenario10_emptyToolRejected() {
        assertFalse(OrionToolOutcomeVerifier.verify("do work", "shell", "").accept)
    }

    @Test fun scenario10_errorToolRejected() {
        assertFalse(OrionToolOutcomeVerifier.verify("do work", "shell", "error: not found").accept)
    }

    @Test fun truthUi_contextCard() {
        val p = OrionChatTruthUi.parseNote("[CONTEXT]\npacks=2")
        assertNotNull(p)
        assertEquals(OrionChatTruthUi.EventKind.CONTEXT, p!!.kind)
    }

    @Test fun truthUi_verifyCard() {
        val p = OrionChatTruthUi.parseNote("[VERIFY:REJECT]\nbad result")
        assertNotNull(p)
        assertTrue(p!!.isFailure)
    }

    @Test fun truthUi_plainNotCard() {
        assertEquals(null, OrionChatTruthUi.parseNote("Hello there"))
    }

    @Test fun planNote_requiresTwoSteps() {
        assertEquals(null, PlanNote.format(listOf("only one")))
        assertNotNull(PlanNote.format(listOf("step a", "step b")))
    }

    @Test fun stepTracker_runningThenSuccess() {
        val lines = listOf(
            StepLine(1_000_000_000L, "me", "open settings"),
            StepLine(2_000_000_000L, "note", "[TASK:RUNNING]\nRunning open_app"),
            StepLine(3_000_000_000L, "note", "[TASK:RESULT]\nopen_app — opened Settings")
        )
        val steps = StepTracker.derive(lines)
        assertEquals(1, steps.size)
        assertEquals(StepStatus.SUCCESS, steps[0].status)
    }

    @Test fun brainBlocksAmbiguous() {
        // prepare needs Context — only assert interpreter gate used by pipeline
        val r = OrionContextualIntentInterpreter.resolve("continue", emptyList(), "")
        assertTrue(r.needsClarification)
    }
}
