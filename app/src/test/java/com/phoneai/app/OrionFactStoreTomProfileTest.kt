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
class OrionFactStoreTomProfileTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("orion_facts.db")
    }

    @After fun tearDown() {
        context.deleteDatabase("orion_facts.db")
    }

    @Test fun ownerPreferencePersistsAndCannotBeOverwrittenByInference() {
        assertTrue(OrionFactStore.tomObserve(
            context, "response_detail", "detailed", 1.0, "I prefer detailed answers", owner = true
        ))
        assertFalse(OrionFactStore.tomObserve(
            context, "response_detail", "brief", 0.99, "single ambiguous short reply"
        ))
        val saved = OrionFactStore.tomProfile(context).single { it.dimension == "response_detail" }
        assertEquals("detailed", saved.value)
        assertEquals("owner", saved.source)
        assertEquals(1.0, saved.confidence, 0.0)
        assertEquals("I prefer detailed answers", saved.evidence)
    }

    @Test fun inferredPreferenceNeedsEvidenceAndMinimumConfidence() {
        assertFalse(OrionFactStore.tomObserve(context, "tone", "casual", 0.4, "weak signal"))
        assertTrue(OrionFactStore.tomObserve(context, "tone", "calm", 0.7, "explicit correction"))
        assertEquals("calm", OrionFactStore.tomProfile(context).single().value)
    }
}
