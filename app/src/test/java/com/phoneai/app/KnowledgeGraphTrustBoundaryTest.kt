package com.phoneai.app

import android.content.Context
import org.junit.After
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
class KnowledgeGraphTrustBoundaryTest {
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

    @Test fun extractedGraphFactsRemainProposalsUntilOwnerApproval() {
        assertEquals(1, KnowledgeGraph.learnFrom(context, "I prefer concise practical explanations."))
        assertTrue(KnowledgeGraph.all(context).isEmpty())
        assertTrue(KnowledgeGraph.promptBlock(context, "concise explanations").isBlank())
        assertTrue(KnowledgeGraph.query(context, "user").contains("no owner-approved"))

        val proposal = MemoryStore.all(context).single()
        assertEquals("pending", proposal.reviewStatus)
        assertFalse(MemoryGovernancePolicy.canUseInPrompt(proposal.reviewStatus, proposal.sensitivity))

        assertTrue(MemoryStore.setReviewStatus(context, proposal.id, "approved"))
        assertTrue(KnowledgeGraph.promptBlock(context, "concise explanations").contains("concise"))
        assertTrue(KnowledgeGraph.query(context, "user").contains("owner-approved data"))
    }

    @Test fun directGraphLinkCannotCreateTrustedFact() {
        assertTrue(KnowledgeGraph.link(context, "user", "prefers", "offline tools"))
        assertTrue(KnowledgeGraph.all(context).isEmpty())
        assertEquals("pending", MemoryStore.all(context).single().reviewStatus)
    }

    @Test fun revokingApprovalImmediatelyRemovesGraphRetrieval() {
        val memory = MemoryStore.addOwnerConfirmed(context, "fact", "User prefers offline tools")!!
        assertTrue(KnowledgeGraph.promptBlock(context, "offline tools").contains("offline"))
        assertTrue(MemoryStore.setReviewStatus(context, memory.id, "rejected"))
        assertTrue(KnowledgeGraph.promptBlock(context, "offline tools").isBlank())
        assertTrue(KnowledgeGraph.all(context).isEmpty())
    }
}
