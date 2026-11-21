package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionSelfReviewTest {
    @Test fun greetingsAreNotReviewedButJobsAre() {
        assertFalse(OrionSelfReview.worthReviewing("hi there", "Hello! " .repeat(60)))
        assertTrue(OrionSelfReview.worthReviewing("research the best way to build a trading journal app", "x".repeat(300)))
    }
    @Test fun okVerdictKeepsTheDraft() {
        val v = OrionSelfReview.parse("VERDICT: OK", "draft")
        assertTrue(v.ok); assertEquals("draft", v.text)
    }
    @Test fun reviseVerdictReturnsTheCorrection() {
        val fix = "Here is the corrected answer with the missing step added and the unverified claim removed."
        val v = OrionSelfReview.parse("VERDICT: REVISE: claimed a test passed that never ran\n" + fix, "draft")
        assertFalse(v.ok); assertEquals(fix, v.text); assertTrue(v.reason.contains("never ran"))
    }
    @Test fun unclearReplyFallsBackToTheDraft() {
        assertTrue(OrionSelfReview.parse("Looks fine to me", "draft").ok)
        assertTrue(OrionSelfReview.parse("VERDICT: REVISE: bad\nshort", "draft").ok)
    }
    @Test fun voiceKeepsThePointAndDropsTheGraph() {
        val spoken = OrionSpeechTextPolicy.spoken("The setup is valid.\n| Pair | Bias |\n|---|---|\n| EURUSD | long |\nSaved at /workspace/frames/scene_001.jpg with sha256 1ce2de30aabbccdd1122. Second. Third. Fourth. Fifth. Sixth.")
        assertFalse(spoken.contains("|")); assertFalse(spoken.contains("/workspace")); assertFalse(spoken.contains("1ce2de30"))
        assertTrue(spoken.startsWith("The setup is valid.")); assertTrue(spoken.endsWith("The rest is on screen."))
    }
}
