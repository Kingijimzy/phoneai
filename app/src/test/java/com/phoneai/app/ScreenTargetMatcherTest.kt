package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTargetMatcherTest {
    @Test fun normalizesAccessibleLabelsAndCommonAliases() {
        assertEquals("search apps games", ScreenTargetMatcher.normalize("Search apps & games"))
        assertTrue(ScreenTargetMatcher.aliases("tap the search button").contains("search"))
        assertTrue(ScreenTargetMatcher.aliases("go back").contains("navigate up"))
    }

    @Test fun exactAndAliasMatchesOutrankLoosePartialMatches() {
        val exact = ScreenTargetMatcher.score("search button", "Search", clickable = true)
        val descriptive = ScreenTargetMatcher.score("search button", "Search apps & games", clickable = true)
        val unrelated = ScreenTargetMatcher.score("search button", "Games for you", clickable = false)
        assertTrue(exact >= 100)
        assertTrue(descriptive >= 100)
        assertTrue(unrelated < 55)
    }

    @Test fun doesNotMatchBlankLabelsOrUnrelatedTargets() {
        assertEquals(0, ScreenTargetMatcher.score("search button", "", clickable = true))
        assertTrue(ScreenTargetMatcher.score("search button", "Install", clickable = true) < 55)
    }
}
