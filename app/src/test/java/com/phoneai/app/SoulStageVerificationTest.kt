package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Stage 7 regression gates for high-impact cross-stage invariants.
 * These tests intentionally exercise local state only and never dispatch tools or call providers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoulStageVerificationTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("orion_soul_state_v1", Context.MODE_PRIVATE)
            .edit().putString("state_json", "{}").commit()
        SoulSubagentCoordinator.clearForTest(context)
    }

    @Test fun sensitiveMemoryCandidateIsNotAcceptedForNudge() {
        val candidate = SoulEngineState.detectExplicitMemoryCandidate("remember my password is hunter2")
        assertNull("Credentials must never become a memory suggestion", candidate)

        val accepted = SoulEngineState.proposeMemoryNudge(
            context,
            category = "preference",
            summary = "My API key is secret-value",
            dedupeKey = "stage7-secret-${UUID.randomUUID()}"
        )
        assertFalse("Sensitive values must be rejected before persistence", accepted)
    }

    @Test fun memorySuggestionRequiresReviewBeforeItBecomesLesson() {
        val unique = UUID.randomUUID().toString()
        val summary = "I prefer concise summaries for stage seven $unique"
        assertTrue(SoulEngineState.proposeMemoryNudge(
            context,
            category = "preference",
            summary = summary,
            dedupeKey = unique
        ))

        val pending = SoulEngineState.snapshot(context).memoryNudges.first { it.dedupeKey == unique }
        assertEquals(NudgeStatus.PENDING, pending.status)
        assertTrue(SoulEngineState.snapshot(context).lessons.none { it.summary == summary })

        assertTrue(SoulEngineState.reviewMemoryNudge(context, pending.id, NudgeStatus.SAVED))
        assertEquals(
            NudgeStatus.SAVED,
            SoulEngineState.snapshot(context).memoryNudges.first { it.id == pending.id }.status
        )
        assertTrue(SoulEngineState.snapshot(context).lessons.any { it.summary == summary })
    }

    @Test fun dismissingMemorySuggestionDoesNotCreateLesson() {
        val unique = UUID.randomUUID().toString()
        val summary = "I prefer a compact verification report $unique"
        assertTrue(SoulEngineState.proposeMemoryNudge(
            context,
            category = "preference",
            summary = summary,
            dedupeKey = unique
        ))
        val pending = SoulEngineState.snapshot(context).memoryNudges.first { it.dedupeKey == unique }
        assertTrue(SoulEngineState.reviewMemoryNudge(context, pending.id, NudgeStatus.DISMISSED))
        assertTrue(SoulEngineState.snapshot(context).lessons.none { it.summary == summary })
    }

    @Test fun watcherSubagentsNeverEnableDisabledPacks() {
        AgentPolicy.setEnabled(context, "gaming_hobby", false)
        SoulSubagentCoordinator.run(context, force = true)
        assertFalse("Verification and reporting must not enable capabilities", AgentPolicy.enabled(context, "gaming_hobby"))
    }
}
