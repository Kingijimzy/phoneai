package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the shared continuity path across transcript, decisions, graph and task checkpoint. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionContinuityIntegrationTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        AgentCheckpointStore.clear(context)
        ExperienceStore.clear(context)
        ChatHistoryStore.clearAll(context)
        OrionDecisionHistoryStore.resetForTests()
        context.deleteDatabase("orion_decisions.db")
    }

    @After fun tearDown() {
        AgentCheckpointStore.clear(context)
        OrionDecisionHistoryStore.resetForTests()
    }

    @Test fun continueRehydratesOriginalObjectiveTranscriptDecisionGraphAndCheckpoint() {
        val original = "Audit Orion provider switching and task recovery reliability"
        val userEvidence = "Please preserve the Orion provider switching and task recovery requirements."
        ChatHistoryStore.save(context, listOf(
            "me" to userEvidence,
            "ai" to "We agreed to keep provider-neutral conversation history and resume from durable task checkpoints."
        ))

        val evidence = OrionConversationRepository.get(context)
            .findTranscriptEvidence(context, "user", userEvidence)
        assertNotNull("Decision provenance must be grounded in a stored user turn", evidence)
        val decision = OrionDecisionHistoryStore.get(context).record(
            topic = "Orion continuity",
            statement = "Keep provider-neutral conversation history and durable task checkpoints",
            status = "ACCEPTED",
            rationale = "Provider changes must not erase the user's active objective or progress.",
            rejectedAlternative = "",
            rejectionReason = "",
            sourceRole = "user",
            sessionId = evidence!!.first,
            turnId = evidence.second,
            sourceExcerpt = userEvidence,
            supersedesId = null
        )
        assertTrue(decision.startsWith("Recorded decision"))

        val graph = OrionObjectiveGraph.parse(
            """{"objectives":[{"id":"provider","title":"Verify provider-neutral conversation restoration"},{"id":"recovery","title":"Verify durable task recovery","dependsOn":["provider"]}]}""",
            original
        ) as OrionObjectiveGraph.ParseResult.Valid
        assertTrue(OrionObjectiveGraph.save(context, graph.graph))

        assertTrue(AgentCheckpointStore.begin(context, original))
        val args = org.json.JSONObject().put("name", "Settings")
        assertTrue(AgentCheckpointStore.beginStep(context, "open_app", args))
        assertTrue(AgentCheckpointStore.record(context, "open_app", args, "Opened Settings"))
        assertTrue(AgentCheckpointStore.pause(context))
        ExperienceStore.recordFailure(context, original, "open_app", "error: target not found")

        // Provider-neutral restoration must retain the same stored user/assistant turns.
        val restoredTurns = ConversationContextPolicy.recent(ChatHistoryStore.load(context))
        assertTrue(restoredTurns.any { it.first == "user" && it.second == userEvidence })
        assertTrue(restoredTurns.any { it.first == "assistant" && it.second.contains("provider-neutral") })

        val prompt = OrionCentralBrain.contextFor(context, "Continue the work")
        assertTrue(prompt.contains("TASK RESUME INSTRUCTIONS"))
        assertTrue(prompt.contains(original))
        assertTrue(prompt.contains("PREVIOUS DISCUSSIONS FOR THIS OBJECTIVE"))
        assertTrue(prompt.contains("DECISIONS AND UNRESOLVED QUESTIONS"))
        assertTrue(prompt.contains("Keep provider-neutral conversation history"))
        assertTrue(prompt.contains("DURABLE OBJECTIVE GRAPH"))
        assertTrue(prompt.contains("Next objective candidates"))
        assertTrue(prompt.contains("open_app"))
        assertTrue(prompt.contains("Opened Settings"))
        assertTrue(prompt.contains("RELEVANT EXECUTION LESSONS"))
        assertTrue(prompt.contains("target_not_found"))
    }
}
