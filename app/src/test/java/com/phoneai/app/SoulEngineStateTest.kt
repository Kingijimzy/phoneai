package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoulEngineStateTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("orion_soul_state_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun sessionGoalModeWatchingAndLessonsSurviveFreshSnapshot() {
        assertTrue(SoulEngineState.setGoal(context, "Summarize my saved tasks", SoulEngineState.Mode.COMMAND))
        assertTrue(SoulEngineState.setWatching(context, listOf("morning brief", "end-of-day recap")))
        assertTrue(SoulEngineState.recordLesson(context, "routing", "Check the live capability registry before planning.", "success"))

        val restored = SoulEngineState.snapshot(context)
        assertEquals("Summarize my saved tasks", restored.goal)
        assertEquals(SoulEngineState.Mode.COMMAND, restored.mode)
        assertEquals(listOf("morning brief", "end-of-day recap"), restored.watching)
        assertTrue(restored.lessons.any { it.summary.contains("capability registry") })
        assertTrue(restored.lastTick > 0)
    }

    @Test fun successfulMultiStepCheckpointCreatesProposalNotExecutableRoute() {
        val checkpoint = JSONObject()
            .put("status", "completed")
            .put("command", "Summarize my saved tasks")
            .put("steps", JSONArray()
                .put(JSONObject().put("tool", "task_list").put("result", "success"))
                .put(JSONObject().put("tool", "read_screen").put("result", "success")))
        assertTrue(SoulEngineState.learnFromCompletedCheckpoint(context, checkpoint))
        val proposal = SoulEngineState.snapshot(context).proposals.single()
        assertEquals(SoulEngineState.ProposalKind.ROUTE, proposal.kind)
        assertEquals(SoulEngineState.ProposalStatus.PENDING, proposal.status)
        assertEquals(1, proposal.evidenceCount)
        assertTrue(proposal.detail.contains("Planning guidance only"))
    }

    @Test fun riskySensitiveGoalsDoNotProduceReusableRouteCandidates() {
        val checkpoint = JSONObject()
            .put("status", "completed")
            .put("command", "Transfer money from my bank account")
            .put("steps", JSONArray()
                .put(JSONObject().put("tool", "task_list").put("result", "success"))
                .put(JSONObject().put("tool", "read_screen").put("result", "success")))
        assertTrue(SoulEngineState.learnFromCompletedCheckpoint(context, checkpoint))
        assertTrue(SoulEngineState.snapshot(context).proposals.isEmpty())
    }

    @Test fun reviewRequiresPendingCandidateAndOnlyRetainsRegisteredTools() {
        val checkpoint = JSONObject()
            .put("status", "completed")
            .put("command", "Summarize my saved tasks")
            .put("steps", JSONArray()
                .put(JSONObject().put("tool", "task_list").put("result", "success"))
                .put(JSONObject().put("tool", "read_screen").put("result", "success")))
        SoulEngineState.learnFromCompletedCheckpoint(context, checkpoint)
        val proposal = SoulEngineState.snapshot(context).proposals.single()
        assertTrue(SoulEngineState.reviewProposal(context, proposal.id, SoulEngineState.ProposalStatus.ACCEPTED))
        val accepted = SoulEngineState.snapshot(context).proposals.single()
        assertEquals(SoulEngineState.ProposalStatus.ACCEPTED, accepted.status)
        assertTrue(accepted.allowedTools.all { it in ToolRegistry.names() })
        assertFalse(SoulEngineState.reviewProposal(context, proposal.id, SoulEngineState.ProposalStatus.REJECTED))
    }

    @Test fun watcherStatusPersistsAcrossSnapshots() {
        assertTrue(SoulEngineState.recordWatcherStatus(context, "limited", "local_capability_audit"))
        val snapshot = SoulEngineState.snapshot(context)
        assertEquals("limited", snapshot.watcherStatus)
        assertEquals("local_capability_audit", snapshot.watcherAction)
        assertTrue(snapshot.watcherAt > 0L)
    }

    @Test fun watcherMaintenanceFindingsBecomeDeduplicatedReviewProposals() {
        assertTrue(SoulEngineState.proposeWatchIssue(
            context, "readiness", "Soul watcher found: screen_perception_unavailable",
            "Review the Capability board and re-run the local capability audit."
        ))
        assertTrue(SoulEngineState.proposeWatchIssue(
            context, "readiness", "Soul watcher found: screen_perception_unavailable",
            "Review the Capability board and re-run the local capability audit."
        ))
        val proposals = SoulEngineState.snapshot(context).proposals
        assertEquals(1, proposals.size)
        assertEquals(SoulEngineState.ProposalKind.TOOL_GUIDANCE, proposals.single().kind)
        assertEquals(SoulEngineState.ProposalStatus.PENDING, proposals.single().status)
        assertTrue(proposals.single().detail.contains("proposal only"))
    }

    @Test fun memoryNudgeIsDeduplicatedAndCanBeSavedOrDismissed() {
        assertTrue(SoulEngineState.proposeMemoryNudge(
            context, "preference", "Prefers concise trading explanations", "prefers concise trading explanations"
        ))
        val pending = SoulEngineState.snapshot(context).memoryNudges.single()
        assertEquals(SoulEngineState.NudgeStatus.PENDING, pending.status)
        assertFalse(SoulEngineState.proposeMemoryNudge(
            context, "preference", "Prefers concise trading explanations", "prefers concise trading explanations"
        ))
        assertTrue(SoulEngineState.reviewMemoryNudge(context, pending.id, SoulEngineState.NudgeStatus.SAVED))
        assertTrue(SoulEngineState.snapshot(context).lessons.any { it.summary == pending.summary && it.outcome == "user_saved_memory" })
        assertFalse(SoulEngineState.reviewMemoryNudge(context, pending.id, SoulEngineState.NudgeStatus.DISMISSED))
    }

    @Test fun memoryNudgeCooldownAndDisableRulesAreRespected() {
        assertTrue(SoulEngineState.proposeMemoryNudge(context, "preference", "Likes concise summaries", "likes concise summaries"))
        assertFalse(SoulEngineState.proposeMemoryNudge(context, "routine", "Prefers short morning brief", "short morning brief"))
        assertTrue(SoulEngineState.configureMemoryNudges(context, enabled = false, cooldownMillis = 3_600_000L))
        assertFalse(SoulEngineState.proposeMemoryNudge(context, "preference", "Uses dark mode", "uses dark mode"))
        val rules = SoulEngineState.snapshot(context).memoryNudgeRules
        assertFalse(rules.enabled)
        assertTrue(rules.cooldownMillis >= 3_600_000L)
    }

    @Test fun sensitiveMemoryCandidatesAreRejected() {
        assertFalse(SoulEngineState.proposeMemoryNudge(context, "account", "My password is abc123", "password"))
        assertFalse(SoulEngineState.proposeMemoryNudge(context, "security", "Remember this seed phrase", "seed phrase"))
        assertTrue(SoulEngineState.snapshot(context).memoryNudges.isEmpty())
    }

    @Test fun secretsAndUrlsAreNotKeptInSoulLabels() {
        assertTrue(SoulEngineState.setGoal(context, "Use api_key=abc123 to open https://example.com/private"))
        val saved = context.getSharedPreferences("orion_soul_state_v1", Context.MODE_PRIVATE).getString("state_json", "")!!
        assertFalse(saved.contains("abc123"))
        assertFalse(saved.contains("https://example.com/private"))
        assertTrue(saved.contains("[REDACTED]") || saved.contains("[URL]"))
    }
}
