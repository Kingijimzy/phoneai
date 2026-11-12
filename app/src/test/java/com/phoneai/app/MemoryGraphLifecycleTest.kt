package com.phoneai.app

import android.content.Context
import org.junit.After
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
class MemoryGraphLifecycleTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        MemoryStore.clear(context)
        OrionKnowledgeGraph.clear(context)
    }

    @After fun tearDown() {
        MemoryStore.clear(context)
        OrionKnowledgeGraph.clear(context)
    }

    @Test fun editingApprovedMemoryRemovesOldGraphEdgesUntilOwnerReview() {
        val saved = MemoryStore.addOwnerConfirmed(
            context, "preference", "User prefers concise practical explanations"
        )!!
        assertTrue(OrionKnowledgeGraph.relatedMemoryIds(context, "concise practical").contains(saved.id))

        assertTrue(MemoryStore.update(
            context, saved.id, null, "User prefers detailed step by step explanations"
        ))
        assertFalse(OrionKnowledgeGraph.relatedMemoryIds(context, "concise practical").contains(saved.id))
        assertFalse(OrionKnowledgeGraph.relatedMemoryIds(context, "step detailed").contains(saved.id))

        assertTrue(MemoryStore.setReviewStatus(context, saved.id, "approved"))
        assertTrue(OrionKnowledgeGraph.relatedMemoryIds(context, "step detailed").contains(saved.id))
    }

    @Test fun rejectingApprovedMemoryRemovesItsGraphEdges() {
        val saved = MemoryStore.addOwnerConfirmed(
            context, "project", "Orion project uses durable shared task context"
        )!!
        assertTrue(OrionKnowledgeGraph.relatedMemoryIds(context, "durable shared").contains(saved.id))
        assertTrue(MemoryStore.setReviewStatus(context, saved.id, "rejected"))
        assertFalse(OrionKnowledgeGraph.relatedMemoryIds(context, "durable shared").contains(saved.id))
    }

    @Test fun explicitEntityRelationshipsAreIndexedAndSearchable() {
        val forex = MemoryStore.addOwnerConfirmed(context, "fact", "I trade forex")!!
        val football = MemoryStore.addOwnerConfirmed(context, "fact", "I like football")!!

        val forexMatches = OrionKnowledgeGraph.relatedMemoryIds(context, "forex")
        assertTrue(forexMatches.contains(forex.id))
        assertFalse(forexMatches.contains(football.id))
    }

    @Test fun twoHopGraphNeighborsReachThePromptAlongsideLexicalMatches() {
        val ownerFact = MemoryStore.addOwnerConfirmed(context, "fact", "My team is Arsenal")!!
        val leagueFact = MemoryStore.addOwnerConfirmed(context, "fact", "Arsenal plays in Premier League")!!

        val prompt = MemoryStore.promptBlock(context, "Premier League", limit = 2)
        assertTrue("Two-hop owner/entity fact must reach prompt recall", prompt.contains(ownerFact.text))
        assertTrue("Direct lexical fact must remain in prompt recall", prompt.contains(leagueFact.text))
    }

    @Test fun existingApprovedMemoriesAreRebuiltIntoEntityGraphOnFirstRecall() {
        val saved = MemoryStore.addOwnerConfirmed(
            context, "fact", "My sister Amaka lives in Lagos"
        )!!
        OrionKnowledgeGraph.clear(context)
        val reached = OrionKnowledgeGraph.relatedMemoryIds(context, "Amaka")
        assertTrue("Legacy approved memory should be indexed automatically", reached.contains(saved.id))
        val snapshot = OrionKnowledgeGraph.snapshot(context)
        assertTrue(snapshot.indexedMemories > 0)
        assertTrue(snapshot.entities > 0)
        assertTrue(snapshot.recentEntities.any { it.contains("amaka") || it.contains("lagos") })
    }

    @Test fun graphExplorerReturnsOnlyApprovedConnectedSourceMemories() {
        val approved = MemoryStore.addOwnerConfirmed(context, "fact", "My sister Amaka lives in Lagos")!!
        val pending = MemoryStore.addObservedProposal(context, "fact", "My friend Amaka visits Lagos", 0.6, "test")!!
        val connected = OrionKnowledgeGraph.relatedMemories(context, "Amaka")
        assertTrue(connected.any { it.id == approved.id })
        assertFalse(connected.any { it.id == pending.id })
    }

    @Test fun graphRecallNeverBypassesOwnerApprovalOrSensitivityFilters() {
        val rejected = MemoryStore.addOwnerConfirmed(context, "fact", "My team is Arsenal")!!
        assertTrue(MemoryStore.setReviewStatus(context, rejected.id, "rejected"))
        val approved = MemoryStore.addOwnerConfirmed(context, "fact", "Arsenal plays in Premier League")!!

        val prompt = MemoryStore.promptBlock(context, "Premier League", limit = 6)
        val graphIds = OrionKnowledgeGraph.relatedMemoryIds(context, "Premier League")
        assertTrue(graphIds.contains(approved.id))
        assertFalse(graphIds.contains(rejected.id))
        assertTrue(prompt.contains(approved.text))
        assertFalse(prompt.contains(rejected.text))
    }
}
