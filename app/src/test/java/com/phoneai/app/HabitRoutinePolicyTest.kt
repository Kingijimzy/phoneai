package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitRoutinePolicyTest {
    @Test fun categorizesLocalTimeIntoBroadDayparts() {
        assertEquals("night", HabitRoutinePolicy.dayPart(2))
        assertEquals("morning", HabitRoutinePolicy.dayPart(8))
        assertEquals("afternoon", HabitRoutinePolicy.dayPart(13))
        assertEquals("evening", HabitRoutinePolicy.dayPart(19))
        assertEquals("night", HabitRoutinePolicy.dayPart(23))
    }

    @Test fun requiresThreeDistinctActiveDaysAndRespectsCooldown() {
        val now = 1_000_000_000L
        assertFalse(HabitRoutinePolicy.shouldSuggest(2, 0L, now))
        assertTrue(HabitRoutinePolicy.shouldSuggest(3, 0L, now))
        assertFalse(HabitRoutinePolicy.shouldSuggest(4, now - 1L, now))
        assertTrue(HabitRoutinePolicy.shouldSuggest(4, now - HabitRoutinePolicy.SUGGESTION_COOLDOWN_MS, now))
        assertFalse(HabitRoutinePolicy.shouldSuggest(4, now + 1L, now))
    }
}
