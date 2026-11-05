package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionBrainPipelineTest {

    @Test
    fun continuePhraseIsNotANewBlindCommand() {
        val res = OrionContextualIntentInterpreter.resolve(
            "continue",
            recentTurns = listOf("user" to "Write a calculator and test it"),
            activeObjective = "Write a calculator and test it"
        )
        assertEquals(OrionContextualIntentInterpreter.Kind.CONTINUE_OBJECTIVE, res.kind)
        assertFalse(res.needsClarification)
        assertTrue(res.contextBlock.contains("Resolved objective", ignoreCase = true))
    }

    @Test
    fun ambiguousContinueWithoutHistoryNeedsClarification() {
        val res = OrionContextualIntentInterpreter.resolve(
            "continue",
            recentTurns = emptyList(),
            activeObjective = ""
        )
        assertEquals(OrionContextualIntentInterpreter.Kind.AMBIGUOUS_REFERENCE, res.kind)
        assertTrue(res.needsClarification)
    }

    @Test
    fun musicOpenWebResultIsRejected() {
        val v = OrionToolOutcomeVerifier.verify(
            userGoal = "open music app",
            tool = "play_media",
            result = "opened YouTube results for music"
        )
        assertFalse(v.accept)
        assertTrue(v.reason.contains("web YouTube", ignoreCase = true))
    }

    @Test
    fun nativeMusicOpenIsAccepted() {
        val v = OrionToolOutcomeVerifier.verify(
            userGoal = "open music app",
            tool = "play_media",
            result = "opened YouTube Music app"
        )
        assertTrue(v.accept)
    }
}
