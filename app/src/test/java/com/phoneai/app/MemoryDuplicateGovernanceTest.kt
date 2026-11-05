package com.phoneai.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemoryDuplicateGovernanceTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before fun clearBefore() {
        MemoryStore.clear(context)
    }

    @After fun clearAfter() {
        MemoryStore.clear(context)
    }

    @Test fun repeatedAgentObservationCannotDemoteOwnerApprovedMemory() {
        val approved = MemoryStore.addOwnerConfirmed(
            context, "preference", "The owner prefers concise technical explanations"
        )
        assertNotNull(approved)

        val repeated = MemoryStore.addObservedProposal(
            context,
            "preference",
            "The owner prefers concise technical explanations",
            0.55,
            "repeated observation from conversation"
        )

        assertNotNull(repeated)
        assertEquals(approved!!.id, repeated!!.id)
        assertEquals("approved", repeated.reviewStatus)
        assertEquals("owner", repeated.sourceRole)
        assertEquals(1.0, repeated.confidence, 0.0001)
        assertEquals("entered directly by owner", repeated.evidence)
        assertTrue(MemoryStore.promptBlock(context, "concise technical explanations").contains(
            "The owner prefers concise technical explanations"
        ))
    }
}
