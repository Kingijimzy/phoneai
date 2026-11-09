package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionInnerLifeTest {
    @Test fun lowBatteryStrainsMood() =
        assertEquals(OrionInnerLife.Mood.STRAINED, OrionInnerLife.moodFor(0, 10, false, 3, 14))

    @Test fun failingToolsFocus() =
        assertEquals(OrionInnerLife.Mood.FOCUSED, OrionInnerLife.moodFor(1, 80, true, 3, 14))

    @Test fun noLessonsIsRestless() =
        assertEquals(OrionInnerLife.Mood.RESTLESS, OrionInnerLife.moodFor(0, 80, true, 0, 14))

    @Test fun repeatedFailuresBecomeRules() {
        val rules = OrionInnerLife.rulesFrom(listOf(
            "github_search failed 2 times in a row", "github_search failed 3 times in a row", "other failed 1 times"))
        assertEquals(1, rules.size)
        assertTrue(rules[0].contains("github_search"))
    }
}
