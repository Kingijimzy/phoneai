package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrionNarratorTest {
    @Test fun searchLineMentionsQuery() {
        assertEquals("Searching for best ramen.", OrionNarrator.line("web_search", JSONObject().put("query", "best ramen")))
    }
    @Test fun trackerStaysQuietWithinSameCategoryAndCaps() {
        val t = OrionNarrator.Tracker(max = 2)
        assertEquals("Searching that now.", t.next("web_search", null))
        assertNull(t.next("news_search", null))
        assertEquals("Running that and checking the result.", t.next("dev_shell", null))
        assertNull(t.next("file_read", null))
    }
}
