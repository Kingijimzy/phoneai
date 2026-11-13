package com.phoneai.app

import android.content.Context
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
class OrionExecutiveStoreIntegrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun resetStore() {
        context.deleteDatabase("orion_executive.db")
    }

    @Test fun dispatchAttemptIsPersistedBeforeAndAfterExecution() {
        val started = System.currentTimeMillis()
        val handle = OrionExecutiveStore.beginToolDispatch(
            context, "session-1", "Run a local diagnostic", "diagnose",
            "python_exec", JSONObject().put("code", "print(1+1)"), started
        )
        assertNotNull(handle)
        val first = OrionExecutiveStore.dispatchAttempts(context, handle!!.objectiveId).single()
        assertEquals("RUNNING", first.outcome)
        assertEquals("diagnose", first.graphStepId)
        assertTrue(first.evidenceRef.isBlank())

        assertTrue(OrionExecutiveStore.finishDispatchAttempt(
            context, handle.attemptId, OrionExecutiveStore.DispatchOutcome.VERIFIED,
            "evidence-123", "Local verifier passed"
        ))
        val finished = OrionExecutiveStore.dispatchAttempts(context, handle.objectiveId).single()
        assertEquals("VERIFIED", finished.outcome)
        assertEquals("evidence-123", finished.evidenceRef)
        assertFalse(OrionExecutiveStore.finishDispatchAttempt(
            context, handle.attemptId, OrionExecutiveStore.DispatchOutcome.FAILED, "", "duplicate finish"
        ))
    }

    @Test fun verifiedAttemptCannotBeCommittedWithoutEvidence() {
        val handle = OrionExecutiveStore.beginToolDispatch(
            context, "session-2", "Inspect screen", "inspect",
            "read_screen", JSONObject(), System.currentTimeMillis()
        )!!
        assertFalse(OrionExecutiveStore.finishDispatchAttempt(
            context, handle.attemptId, OrionExecutiveStore.DispatchOutcome.VERIFIED, "", "claimed verified"
        ))
        assertEquals("RUNNING", OrionExecutiveStore.dispatchAttempts(context, handle.objectiveId).single().outcome)
    }

    @Test fun differentArgumentsCreateDifferentAttemptReceipts() {
        val first = OrionExecutiveStore.beginToolDispatch(
            context, "session-3", "Run task", "step-a", "shell",
            JSONObject().put("command", "pwd"), 100L
        )!!
        val second = OrionExecutiveStore.beginToolDispatch(
            context, "session-3", "Run task", "step-a", "shell",
            JSONObject().put("command", "whoami"), 101L
        )!!
        assertNotEquals(first.attemptId, second.attemptId)
        assertEquals(2, OrionExecutiveStore.dispatchAttempts(context, first.objectiveId).size)
    }

    @Test fun verifiedTerminalDispatchReceiptRemainsVisibleToCentralBrain() {
        val handle = OrionExecutiveStore.beginToolDispatch(
            context, "session-context", "Verify terminal receipt visibility", "voice-test",
            "voice_test", JSONObject().put("mode", "smoke"), System.currentTimeMillis()
        )!!
        assertTrue(OrionExecutiveStore.finishDispatchAttempt(
            context, handle.attemptId, OrionExecutiveStore.DispatchOutcome.VERIFIED,
            "evidence-voice-smoke-001", "Voice smoke test passed and output was observed"
        ))

        val brainContext = OrionExecutiveStore.contextBlock(context)
        assertTrue(brainContext.contains("phase=COMPLETED"))
        assertTrue(brainContext.contains("outcome=VERIFIED"))
        assertTrue(brainContext.contains("evidence-voice-smoke-001"))
        assertTrue(brainContext.contains("Voice smoke test passed"))
    }


    @Test fun objectiveGraphCompletionRequiresLatestTrustedReceiptAndCompletedDependencies() {
        ChatHistoryStore.clearAll(context)
        ChatHistoryStore.save(context, listOf("user" to "Verify objective evidence integration"))
        assertNotNull(ChatHistoryStore.activeSessionId(context))

        val graph = OrionObjectiveGraph.Graph(
            objective = "Research then summarize",
            nodes = listOf(
                OrionObjectiveGraph.Objective("research", "Research", status = OrionObjectiveGraph.Status.RUNNING),
                OrionObjectiveGraph.Objective("summarize", "Summarize", dependsOn = listOf("research"))
            )
        )
        assertTrue(OrionObjectiveGraph.save(context, graph))

        val firstReceipt = OrionObjectiveGraph.recordExecution(
            context, "research", "web_search", "error-free but superseded result"
        )
        assertNotNull(firstReceipt)
        val latestReceipt = OrionObjectiveGraph.recordExecution(
            context, "research", "web_search", "latest trusted research result"
        )
        assertNotNull(latestReceipt)
        assertTrue(firstReceipt != latestReceipt)

        assertFalse(OrionObjectiveGraph.completeFromEvidence(context, "research", requireNotNull(firstReceipt)))
        assertTrue(OrionObjectiveGraph.completeFromEvidence(context, "research", requireNotNull(latestReceipt)))
        val updated = requireNotNull(OrionObjectiveGraph.load(context))
        assertEquals(OrionObjectiveGraph.Status.COMPLETE, updated.nodes.first { it.id == "research" }.status)
        assertNull(OrionObjectiveGraph.canExecute(updated, "summarize"))
        assertFalse(OrionObjectiveGraph.completeFromEvidence(context, "summarize", "model-invented-evidence"))
    }


    @Test fun objectiveCompletionRejectsEvidenceAfterItsOwnerSessionIsArchived() {
        ChatHistoryStore.clearAll(context)
        ChatHistoryStore.save(context, listOf("me" to "Start objective session"))
        val graph = OrionObjectiveGraph.Graph(
            objective = "Session-bound objective",
            nodes = listOf(OrionObjectiveGraph.Objective("main", "Complete session-bound work", status = OrionObjectiveGraph.Status.RUNNING))
        )
        assertTrue(OrionObjectiveGraph.save(context, graph))
        val receipt = OrionObjectiveGraph.recordExecution(context, "main", "verify_action", "PASS: observed state matches goal")
        assertNotNull(receipt)

        ChatHistoryStore.archiveAndClear(context, listOf("me" to "Archive the objective session"))
        assertNull(ChatHistoryStore.activeSessionId(context))
        assertFalse(OrionObjectiveGraph.completeFromEvidence(context, "main", requireNotNull(receipt)))
    }

}
