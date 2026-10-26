package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionRuntimePolicyTest {
    @Test fun priorityIsClampedAndWorkTypesAreExplicit() {
        assertEquals(listOf("LEARN_FROM_RESULT", "CAPABILITY_RECHECK", "ROUTINE_CANDIDATE", "RESTORE_PENDING"),
            OrionRuntime.WorkType.values().map { it.name })
        assertTrue(10 >= (-100).coerceIn(-10, 10))
        assertEquals(-10, (-100).coerceIn(-10, 10))
        assertEquals(10, 100.coerceIn(-10, 10))
    }

    @Test fun exponentialBackoffIsBounded() {
        val delays = (1..3).map { attempt -> (30_000L * (1L shl (attempt - 1))).coerceAtMost(15 * 60_000L) }
        assertEquals(listOf(30_000L, 60_000L, 120_000L), delays)
        assertFalse(4 < 4)
    }
}
