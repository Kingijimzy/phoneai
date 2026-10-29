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
class SoulP3EngineTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("orion_soul_state_v1", Context.MODE_PRIVATE).edit().clear().commit()
        SoulP3Engine.clearReportsForTest(context)
    }

    private fun checkpoint(command: String, firstTool: String, secondTool: String) = JSONObject()
        .put("status", "completed")
        .put("command", command)
        .put("steps", JSONArray()
            .put(JSONObject().put("tool", firstTool).put("result", "success"))
            .put(JSONObject().put("tool", secondTool).put("result", "success")))

    @Test fun probeComparesCandidateToNoHintBaselineAndComposesOnlyWithRepeatedEvidence() {
        val cp = checkpoint("Summarize my saved tasks", "task_list", "read_screen")
        assertTrue(SoulEngineState.learnFromCompletedCheckpoint(context, cp))
        assertTrue(SoulEngineState.learnFromCompletedCheckpoint(context, cp))
        val candidate = SoulEngineState.snapshot(context).proposals.single()
        assertEquals(2, candidate.evidenceCount)

        val result = SoulP3Engine.run(context, force = true)
        assertEquals(1, result.probesRun)
        assertEquals(1, result.compositionsCreated)
        assertTrue(result.summary.contains("No tools were executed"))

        val probe = SoulP3Engine.probeReport(context)
        assertTrue(probe.contains("candidate_ready_for_review"))
        assertTrue(probe.contains("baseline learned hints 0"))
        val draft = SoulP3Engine.compositionReport(context)
        assertTrue(draft.contains("DRAFT"))
        assertTrue(draft.contains("does not prove better task success"))
        assertEquals(SoulEngineState.ProposalStatus.PENDING, SoulEngineState.snapshot(context).proposals.single().status)
    }

    @Test fun unavailableCandidateToolsBlockComposition() {
        val cp = checkpoint("Summarize my saved tasks", "missing_tool_a", "missing_tool_b")
        assertTrue(SoulEngineState.learnFromCompletedCheckpoint(context, cp))
        assertTrue(SoulEngineState.learnFromCompletedCheckpoint(context, cp))

        val result = SoulP3Engine.run(context, force = true)
        assertEquals(1, result.probesRun)
        assertEquals(0, result.compositionsCreated)
        assertTrue(SoulP3Engine.probeReport(context).contains("blocked_missing_tools"))
        assertTrue(SoulP3Engine.compositionReport(context).contains("No composition drafts yet"))
    }

    @Test fun automaticProbeCooldownDoesNotRepeatTheSamePassImmediately() {
        val cp = checkpoint("Summarize my saved tasks", "task_list", "read_screen")
        assertTrue(SoulEngineState.learnFromCompletedCheckpoint(context, cp))
        assertTrue(SoulEngineState.learnFromCompletedCheckpoint(context, cp))
        assertEquals(1, SoulP3Engine.run(context, force = true).probesRun)
        val second = SoulP3Engine.run(context)
        assertEquals(0, second.probesRun)
        assertTrue(second.summary.contains("cooldown"))
    }
}
