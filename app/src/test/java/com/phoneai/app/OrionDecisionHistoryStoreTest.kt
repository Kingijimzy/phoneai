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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionDecisionHistoryStoreTest {
    private lateinit var context: Context
    private lateinit var store: OrionDecisionHistoryStore

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        OrionDecisionHistoryStore.resetForTests()
        context.deleteDatabase("orion_decisions.db")
        store = OrionDecisionHistoryStore.get(context)
    }

    @After fun tearDown() {
        OrionDecisionHistoryStore.resetForTests()
    }

    @Test fun assistantProposalCannotBeRecordedAsUserAcceptedDecision() {
        val result = store.record(
            "memory architecture", "Use a separate decision ledger", "ACCEPTED",
            "Cleaner provenance", "", "", "assistant", "session-1", "turn-1",
            "I suggest a separate ledger", null
        )
        assertTrue(result.startsWith("error:"))
        assertTrue(store.search("memory architecture").isEmpty())
    }

    @Test fun explicitUserDecisionAndRejectedAlternativeRetainReasonsAndEvidence() {
        val accepted = store.record(
            "memory architecture", "Keep decision history in a local SQLite ledger", "ACCEPTED",
            "It preserves provenance without replacing transcripts", "", "", "user",
            "session-1", "turn-2", "Yes, keep a separate local decision history with reasons.", null
        )
        assertTrue(accepted.startsWith("Recorded decision"))
        val rejected = store.record(
            "memory architecture", "Do not store decisions in assistant prompt summaries", "REJECTED",
            "Prompt summaries can lose source attribution", "Use only rolling prompt summaries",
            "They overwrite history and cannot distinguish proposal from agreement", "user",
            "session-1", "turn-3", "No, don't rely on prompt summaries; preserve the actual decisions.", null
        )
        assertTrue(rejected.startsWith("Recorded decision"))
        val results = store.search("memory architecture")
        assertEquals(2, results.size)
        assertTrue(results.any { it.status == "ACCEPTED" && it.sourceRole == "user" && it.sourceTurnId == "turn-2" })
        assertTrue(results.any { it.status == "REJECTED" && it.rejectedAlternative.contains("rolling prompt") && it.rejectionReason.contains("overwrite") })
    }

    @Test fun acceptedReplacementSupersedesOldRecordWithoutDeletingHistory() {
        val first = store.record(
            "dispatch architecture", "Keep dispatch in a single adapter", "ACCEPTED",
            "One auditable boundary", "", "", "user", "session-2", "turn-4",
            "Keep one adapter boundary.", null
        )
        val firstId = Regex("\\[([^]]+)\\]").find(first)!!.groupValues[1]
        val second = store.record(
            "dispatch architecture", "Add one explicit executor owner per backend family", "ACCEPTED",
            "Dedicated ownership makes coverage testable", "", "", "user", "session-2", "turn-5",
            "Yes, make each backend family have one explicit executor owner.", firstId
        )
        assertTrue(second.startsWith("Recorded decision"))
        val results = store.search("dispatch architecture")
        assertEquals(2, results.size)
        assertTrue(results.any { it.id == firstId && it.status == "SUPERSEDED" })
        assertTrue(results.any { it.status == "ACCEPTED" && it.supersedesId == firstId })
    }

    @Test fun unresolvedQuestionsAndAssistantProposalsRemainDistinct() {
        store.record(
            "provider routing", "Should retries use exponential backoff?", "UNRESOLVED",
            "", "", "", "user", "session-3", null, "We have not decided the retry backoff yet.", null
        )
        store.record(
            "provider routing", "Try capped exponential backoff", "PROPOSED",
            "It may reduce repeated rate-limit failures", "", "", "assistant",
            "session-3", null, "A possible approach is capped exponential backoff.", null
        )
        val results = store.search("provider routing")
        assertTrue(results.any { it.status == "UNRESOLVED" && it.sourceRole == "user" })
        assertTrue(results.any { it.status == "PROPOSED" && it.sourceRole == "assistant" })
    }
}
