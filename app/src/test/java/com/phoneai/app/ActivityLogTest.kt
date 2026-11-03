package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ActivityLogTest {
    @Test fun newestFirstAndCapped() {
        var l = emptyList<ActivityLog.Entry>()
        for (i in 1..5) l = ActivityLog.add(l, ActivityLog.Entry(i.toLong(), "t$i", "x"), 3)
        assertEquals(listOf("t5", "t4", "t3"), l.map { it.title })
    }
    @Test fun roundTrips() {
        val l = listOf(ActivityLog.Entry(5L, "A", "b c"))
        assertEquals(l, ActivityLog.decode(ActivityLog.encode(l)))
        assertTrue(ActivityLog.decode("garbage").isEmpty())
    }
    @Test fun agoText() {
        assertEquals("just now", ActivityLog.ago(1000L, 1000L))
        assertEquals("5m ago", ActivityLog.ago(5 * 60_000L, 0L))
        assertEquals("2h ago", ActivityLog.ago(2 * 3_600_000L, 0L))
        assertEquals("3d ago", ActivityLog.ago(3 * 86_400_000L, 0L))
    }
}
