package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrionRulesTest {
    @Test fun detectsStandingInstructions() {
        assertEquals("Reply in short sentences", OrionRules.detect("From now on, reply in short sentences."))
        assertEquals("Never open Chrome for searches", OrionRules.detect("never open Chrome for searches"))
        assertEquals("Do not say sorry → use a one-line fix", OrionRules.detect("No, don't say sorry, use a one-line fix"))
    }
    @Test fun ignoresQuestionsAndChatter() {
        assertNull(OrionRules.detect("Should I always back up my phone?"))
        assertNull(OrionRules.detect("open the weather"))
    }
    @Test fun mergeDedupesAndCaps() {
        assertEquals(listOf("b", "A"), OrionRules.merge(listOf("a", "b"), "A"))
        assertEquals(25, OrionRules.merge((1..30).map { "r$it" }, "x").size)
    }
    @Test fun retryAfterParsesSecondsAndDates() {
        assertEquals(7, OrionRetryPolicy.parseRetryAfter("7"))
        assertEquals(300, OrionRetryPolicy.parseRetryAfter("9999"))
        assertEquals(-1, OrionRetryPolicy.parseRetryAfter("soon"))
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", java.util.Locale.US).parse("2026-11-14 10:00:00 GMT")!!.time
        assertEquals(30, OrionRetryPolicy.parseRetryAfter("Sat, 14 Nov 2026 10:00:30 GMT", now))
    }
    @Test fun retryableStatuses() {
        assert(OrionRetryPolicy.isRetryable(429)); assert(OrionRetryPolicy.isRetryable(503)); assert(!OrionRetryPolicy.isRetryable(401))
    }
}
