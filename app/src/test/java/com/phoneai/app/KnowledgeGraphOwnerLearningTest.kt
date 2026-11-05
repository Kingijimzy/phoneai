package com.phoneai.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KnowledgeGraphOwnerLearningTest {
    @Test fun explicitOwnerFactsBecomeImmediatelyQueryableButQuestionsAndSecretsDoNot() {
        val context = RuntimeEnvironment.getApplication() as Context
        MemoryStore.clear(context)
        try {
            assertEquals(1, KnowledgeGraph.learnFromOwnerStatement(context, "I trade forex."))
            assertTrue(KnowledgeGraph.all(context).any {
                it.a == "user" && it.rel == "trade" && it.b == "forex"
            })

            assertEquals(0, KnowledgeGraph.learnFromOwnerStatement(context, "Do I trade forex?"))
            assertEquals(0, KnowledgeGraph.learnFromOwnerStatement(context, "My password is hunter2"))
            assertTrue(KnowledgeGraph.all(context).none { it.b.contains("hunter2") })
        } finally {
            MemoryStore.clear(context)
        }
    }
}
