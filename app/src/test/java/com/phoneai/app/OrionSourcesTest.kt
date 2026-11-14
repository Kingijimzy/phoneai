package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OrionSourcesTest {
    @Test fun extractsDistinctDomainsAndTrimsPunctuation() {
        val s = orionSourcesIn("See https://www.example.com/a, and https://news.site.org/x?y=1. Again https://www.example.com/a.")
        assertEquals(listOf("example.com", "news.site.org"), s.map { it.domain })
        assertEquals("https://www.example.com/a", s[0].url)
    }
    @Test fun ignoresTextWithoutLinks() { assertEquals(0, orionSourcesIn("no links here").size) }
    @Test fun capsAtMax() {
        val t = (1..9).joinToString(" ") { "https://s$it.com/p" }
        assertEquals(6, orionSourcesIn(t).size)
    }
}

class OrionFileCardTest {
    @Test fun kindsAndSizes() {
        assertEquals("PDF", orionFileKind("a.PDF", ""))
        assertEquals("IMG", orionFileKind("x", "image/png"))
        assertEquals("CODE", orionFileKind("main.kt", ""))
        assertEquals("2.0 KB", orionHumanSize(2048))
        assertEquals("12 B", orionHumanSize(12))
    }
}
