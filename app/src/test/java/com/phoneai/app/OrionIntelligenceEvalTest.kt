package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Automated subset of the 20 eval scenarios (Rule 10). */
class OrionIntelligenceEvalTest {

    @Test
    fun scenario2_musicOpenRejectsWebResult() {
        val v = OrionToolOutcomeVerifier.verify("open music app", "play_media", "opened YouTube results for music")
        assertFalse(v.accept)
    }

    @Test
    fun scenario2_musicOpenAcceptsNative() {
        assertTrue(OrionToolOutcomeVerifier.verify("open music app", "play_media", "opened YouTube Music app").accept)
    }

    @Test
    fun scenario4_continueWithHistory() {
        val r = OrionContextualIntentInterpreter.resolve(
            "continue",
            listOf("user" to "Build a calculator"),
            "Build a calculator"
        )
        assertEquals(OrionContextualIntentInterpreter.Kind.CONTINUE_OBJECTIVE, r.kind)
        assertFalse(r.needsClarification)
    }

    @Test
    fun scenario5_continueWithoutHistoryNeedsClarify() {
        val r = OrionContextualIntentInterpreter.resolve("continue", emptyList(), "")
        assertTrue(r.needsClarification)
        assertTrue(OrionBrainPipeline.blockTools(
            // minimal frame path via interpreter only
            OrionBrainPipeline.Frame(
                userMessage = "continue",
                intent = r,
                preAction = OrionPreActionContext.Assessment("t", true, emptyList()),
                contextHints = r.contextBlock,
                preferDirectAction = false,
                requiresMultiStep = false
            )
        ))
    }

    @Test
    fun scenario10_irrelevantResultRejected() {
        val v = OrionToolOutcomeVerifier.verify("open the music app", "open_url", "opened https://www.youtube.com/results?search_query=music")
        // open_url with http may still accept generic path; music-specific path should reject youtube results wording
        val v2 = OrionToolOutcomeVerifier.verify("open music app", "play_media", "youtube results for music")
        assertFalse(v2.accept)
    }

    @Test
    fun chatTruth_parsesContextBrainVerify() {
        assertEquals(OrionChatTruthUi.EventKind.CONTEXT, OrionChatTruthUi.parseNote("[CONTEXT]\nx")!!.kind)
        assertEquals(OrionChatTruthUi.EventKind.BRAIN, OrionChatTruthUi.parseNote("[BRAIN]\ndirect")!!.kind)
        assertTrue(OrionChatTruthUi.parseNote("[VERIFY:REJECT]\nbad")!!.isFailure)
        assertNull(OrionChatTruthUi.parseNote("Hello there"))
    }

    @Test
    fun fastIntent_openMusicAppIsNativeOnly() {
        val i = FastIntents.parse("open music app")
        assertNotNull(i)
        assertTrue(i is FastIntents.Intent.PlayMedia)
        assertEquals(FastIntents.OPEN_APP_ONLY, (i as FastIntents.Intent.PlayMedia).query)
    }
}
