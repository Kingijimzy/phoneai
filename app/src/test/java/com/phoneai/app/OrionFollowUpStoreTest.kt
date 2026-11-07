package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionFollowUpStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearFollowUps(context)
    }

    @After fun tearDown() {
        clearFollowUps(context)
    }

    @Test fun addFollowUpReturnsId() {
        val now = System.currentTimeMillis()
        val id = OrionFollowUpStore.add(context, "call the bank", dueAt = now + 60 * 60_000L)
        assertFalse(id.isBlank())
        assertTrue(id.length <= 16)
    }

    @Test fun emptyTextIsRejected() {
        assertEquals("", OrionFollowUpStore.add(context, "   "))
    }

    @Test fun pendingReturnsAddedFollowUp() {
        val now = System.currentTimeMillis()
        val id = OrionFollowUpStore.add(context, "review the contract", dueAt = now + 30 * 60_000L)
        val pending = OrionFollowUpStore.pending(context)
        assertEquals(1, pending.size)
        assertEquals(id, pending.first().id)
        assertEquals("review the contract", pending.first().text)
        assertFalse(pending.first().done)
    }

    @Test fun pendingSortedByDueDate() {
        val now = System.currentTimeMillis()
        OrionFollowUpStore.add(context, "later item", dueAt = now + 120 * 60_000L)
        OrionFollowUpStore.add(context, "sooner item", dueAt = now + 10 * 60_000L)
        OrionFollowUpStore.add(context, "no due date")
        val pending = OrionFollowUpStore.pending(context)
        assertEquals(3, pending.size)
        // Soonest due first
        assertEquals("sooner item", pending.first().text)
        assertEquals("no due date", pending.last().text)
    }

    @Test fun dueSoonReturnsItemsWithinWindow() {
        val now = System.currentTimeMillis()
        OrionFollowUpStore.add(context, "due in 15 min", dueAt = now + 15 * 60_000L)
        OrionFollowUpStore.add(context, "due in 5 hours", dueAt = now + 300 * 60_000L)
        val soon = OrionFollowUpStore.dueSoon(context, withinMinutes = 60, now = now)
        assertEquals(1, soon.size)
        assertEquals("due in 15 min", soon.first().text)
    }

    @Test fun overdueReturnsPastDueItems() {
        val now = System.currentTimeMillis()
        OrionFollowUpStore.add(context, "overdue follow-up", dueAt = now - 10 * 60_000L)
        OrionFollowUpStore.add(context, "future follow-up", dueAt = now + 60 * 60_000L)
        val overdue = OrionFollowUpStore.overdue(context, now = now)
        assertEquals(1, overdue.size)
        assertEquals("overdue follow-up", overdue.first().text)
    }

    @Test fun doneMarksFollowUpComplete() {
        val now = System.currentTimeMillis()
        val id = OrionFollowUpStore.add(context, "to be completed", dueAt = now + 60 * 60_000L)
        assertTrue(OrionFollowUpStore.done(context, id))
        val pending = OrionFollowUpStore.pending(context)
        assertEquals(0, pending.size)
    }

    @Test fun doneUnknownIdReturnsFalse() {
        assertFalse(OrionFollowUpStore.done(context, "nonexistent"))
    }

    @Test fun removeFollowUp() {
        val id = OrionFollowUpStore.add(context, "to be removed")
        assertTrue(OrionFollowUpStore.remove(context, id))
        assertEquals(0, OrionFollowUpStore.pending(context).size)
    }

    @Test fun countPending() {
        assertEquals(0, OrionFollowUpStore.countPending(context))
        OrionFollowUpStore.add(context, "one")
        OrionFollowUpStore.add(context, "two")
        assertEquals(2, OrionFollowUpStore.countPending(context))
    }

    private fun clearFollowUps(context: Context) {
        context.getSharedPreferences("orion_followups_v1", Context.MODE_PRIVATE).edit()
            .putString("items", "[]").apply()
    }
}
