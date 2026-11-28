package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionLivingLoopTest {
    private val c get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        OrionSelfModel.clear(c)
        c.getSharedPreferences("orion_lessons", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        OrionFollowUpStore.pending(c).toList().forEach { OrionFollowUpStore.done(c, it.id) }
    }

    @Test fun successResetsTheFailureStreak() {
        OrionSelfModel.record(c, "click_text", false, "error: no match")
        OrionSelfModel.record(c, "click_text", true)
        OrionSelfModel.record(c, "click_text", false, "error: no match")
        assertTrue(OrionSelfModel.degraded(c).isEmpty())
    }

    @Test fun repeatedFailuresAreDetectedAndSecretsRedacted() {
        OrionSelfModel.record(c, "model_status", false, "error: api_key=sk-secret-123 rejected")
        OrionSelfModel.record(c, "model_status", false, "error: api_key=sk-secret-123 rejected")
        val d = OrionSelfModel.degraded(c).single()
        assertEquals("model_status", d.tool)
        assertEquals(2, d.consecutive)
        assertFalse(d.lastReason.contains("sk-secret-123"))
    }

    @Test fun tickLearnsOnceAndQueuesAFollowUp() {
        repeat(2) { OrionSelfModel.record(c, "open_app", false, "error: not found") }
        val first = OrionLivingLoop.tick(c)
        assertTrue(first.contains("open_app"))
        assertTrue(OrionLessons.all(c).single().contains("open_app"))
        assertEquals(1, OrionFollowUpStore.pending(c).size)
        // The same streak is not re-reported.
        assertEquals("", OrionLivingLoop.tick(c))
        assertEquals(1, OrionFollowUpStore.pending(c).size)
    }

    @Test fun promptBlockIsEmptyWhenAllIsWellAndMentionsFailuresOtherwise() {
        assertEquals("", OrionLivingLoop.promptBlock(c))
        repeat(2) { OrionSelfModel.record(c, "open_app", false, "error: not found") }
        assertTrue(OrionLivingLoop.promptBlock(c).contains("open_app"))
    }

    @Test fun lessonsAreDeduplicatedAndCapped() {
        repeat(3) { OrionLessons.learn(c, "same lesson") }
        assertEquals(1, OrionLessons.all(c).size)
        repeat(40) { OrionLessons.learn(c, "lesson $it") }
        assertEquals(30, OrionLessons.all(c).size)
    }

    @Test fun lessonsAreShownRegardlessOfTheRequestsWordingBecauseTheListIsAlreadyCheapAndBounded() {
        // A word-overlap pre-filter was tried and reverted: it is a lexical trick standing in for
        // understanding, not understanding, and it hides a relevant lesson phrased in different words
        // ("signal" vs "connectivity", say). For a list this small, the fix is to show it in full and
        // trust the model's own judgment, not to bolt on a second heuristic beside the first one.
        OrionLessons.learn(c, "the calendar sync keeps failing on weak signal")
        OrionLessons.learn(c, "battery readings must come from the battery tool, never a guess")
        val block = OrionLivingLoop.promptBlock(c, "wassup")
        assertTrue(block.contains("battery"))
        assertTrue(block.contains("calendar"))
    }

    @Test fun lessonsCarryProvenanceAndAnExplicitInstructionToJudgeRelevanceThemselves() {
        OrionLessons.learn(c, "the calendar sync keeps failing on weak signal")
        val block = OrionLivingLoop.promptBlock(c, "wassup")
        assertTrue(block.contains("PROVENANCE"))
        assertTrue(block.contains("not fetched or filtered for this message"))
        assertTrue(block.contains("bring one up only when it actually bears on this reply"))
    }

    @Test fun failingToolsStayUnconditionalRegardlessOfTheRequest() {
        // Operational state to route around, not memory recall: it is never filtered out, even when
        // nothing in the message is about it.
        repeat(2) { OrionSelfModel.record(c, "open_app", false, "error: not found") }
        assertTrue(OrionLivingLoop.promptBlock(c, "wassup").contains("open_app"))
    }
}
