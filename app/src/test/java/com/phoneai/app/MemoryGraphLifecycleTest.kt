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
}
