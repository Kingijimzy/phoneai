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
class OrionFactStoreGraphTest {
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

    @Test fun ownerExtractedTripletsBecomeApprovedCanonicalGraphFacts() {
        OrionFactStore.addOwnerFact(context, "Kingi Jimzy", "favourite_sport", "football")
        val result = OrionFactStore.query(context, "user", "favourite_sport", "football")
        assertTrue(result.contains("user — favourite sport — football"))
        assertTrue(MemoryStore.all(context).any { it.reviewStatus == "approved" && it.text.contains("favourite_sport") })
    }

    @Test fun modelToolTripletsRemainProposalsUntilOwnerReview() {
        OrionFactStore.add(context, "Kingi Jimzy", "favourite_sport", "football")
        val result = OrionFactStore.query(context, "Kingi Jimzy", "favourite_sport", "football")
        assertTrue(result.contains("no owner-approved facts"))
        assertTrue(MemoryStore.all(context).any { it.reviewStatus == "pending" && it.text.contains("favourite_sport") })
    }

    @Test fun forgettingCanonicalFactRemovesItFromTrustedRecall() {
        OrionFactStore.addOwnerFact(context, "Kingi Jimzy", "favourite_sport", "football")
        val result = OrionFactStore.forget(context, "user", "favourite_sport", "football")
        assertTrue(result.contains("forgot 1 fact"))
        assertTrue(OrionFactStore.query(context, "Kingi Jimzy", "favourite_sport", "football").contains("no owner-approved facts"))
    }
}
