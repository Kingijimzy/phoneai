package com.phoneai.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionBehaviourLedgerTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        OrionBehaviourLedger.clear(context)
    }

    @Test fun persistsOutcomeAndEvidenceWithoutPromotingUnverifiedToSuccess() {
        OrionBehaviourLedger.record(context, "background", "worker", "Inspected capability health", "completed_unverified", "No external action was performed", 100L)
        val event = OrionBehaviourLedger.recent(context).single()
        assertEquals("completed_unverified", event.outcome)
        assertEquals("No external action was performed", event.evidence)
        assertTrue(OrionBehaviourLedger.summary(context).contains("completed_unverified"))
    }

    @Test fun retainsOnlyMostRecentBoundedEvents() {
        repeat(OrionBehaviourLedgerTestLimit.VALUE) { i ->
            OrionBehaviourLedger.record(context, "observation", "worker", "event-$i", "observed", now = i.toLong() + 1L)
        }
        val events = OrionBehaviourLedger.recent(context, 120)
        assertEquals(120, events.size)
        assertEquals("event-1", events.first().summary)
        assertEquals("event-119", events.last().summary)
    }

    @Test fun blankEventsAreIgnored() {
        assertEquals(null, OrionBehaviourLedger.record(context, "observation", "worker", "   ", "observed"))
        assertTrue(OrionBehaviourLedger.recent(context).isEmpty())
    }

    private object OrionBehaviourLedgerTestLimit { const val VALUE = 121 }
}
