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
}
