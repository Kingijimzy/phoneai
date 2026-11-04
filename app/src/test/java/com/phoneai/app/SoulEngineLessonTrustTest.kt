package com.phoneai.app

import android.content.Context
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
class SoulEngineLessonTrustTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("orion_soul_state_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun generatedLessonsAreNotRetrievedIntoPromptContext() {
        assertTrue(SoulEngineState.recordLesson(
            context, "routing", "Prefer the experimental unreviewed route", "success"
        ))
        val retrieved = SoulEngineState.relatedContext(context, "experimental unreviewed route")
        assertFalse(retrieved.any { it.contains("experimental unreviewed route") })
    }

    @Test fun onlyOwnerSavedNudgesBecomeRetrievableLessons() {
        assertTrue(SoulEngineState.proposeMemoryNudge(
            context, "preference", "Prefers offline-first tools", "offline-first tools"
        ))
        val nudge = SoulEngineState.snapshot(context).memoryNudges.single()
        assertTrue(SoulEngineState.reviewMemoryNudge(context, nudge.id, SoulEngineState.NudgeStatus.SAVED))
        val retrieved = SoulEngineState.relatedContext(context, "offline-first tools")
        assertTrue(retrieved.any { it.contains("owner-approved lesson") })
    }
}
