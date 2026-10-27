package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class SessionStallTest {
    @Test fun repeatedIdenticalActionsStallTheTask() {
        val s = SessionStall()
        assertNull(s.add("click_text", "{\"text\":\"Search\"}", "clicked"))
        assertNull(s.add("click_text", "{\"text\":\"Search\"}", "clicked"))
        assertNotNull(s.add("click_text", "{\"text\":\"Search\"}", "clicked"))
    }

    @Test fun differentActionsDoNotStall() {
        val s = SessionStall()
        assertNull(s.add("tap", "{\"x\":1}", "tapped"))
        assertNull(s.add("tap", "{\"x\":2}", "tapped"))
        assertNull(s.add("swipe", "{}", "swiped"))
        assertNull(s.add("click_text", "{}", "clicked"))
    }

    @Test fun fourFailuresInARowStall() {
        val s = SessionStall()
        assertNull(s.add("open_app", "{\"name\":\"a\"}", "error: not found"))
        assertNull(s.add("open_app", "{\"name\":\"b\"}", "error: not found"))
        assertNull(s.add("open_app", "{\"name\":\"c\"}", "error: not found"))
        assertNotNull(s.add("open_app", "{\"name\":\"d\"}", "error: not found"))
    }

    @Test fun aSuccessResetsTheFailureRun() {
        val s = SessionStall()
        s.add("a", "{}", "error: x"); s.add("b", "{}", "error: x"); s.add("c", "{}", "error: x")
        assertNull(s.add("d", "{}", "ok"))
        assertNull(s.add("e", "{}", "error: x"))
    }

    @Test fun summaryListsWhatWasTried() {
        val s = SessionStall()
        assertEquals("", s.summary())
        s.add("open_app", "{\"name\":\"Play Store\"}", "opened")
        s.add("tap", "{\"x\":3}", "error: no target")
        val sum = s.summary()
        assertTrue(sum.startsWith("Here is what I tried:"))
        assertTrue(sum.contains("open_app") && sum.contains("→ failed"))
    }
}
