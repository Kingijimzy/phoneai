package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWebSearchTest {
    @Test fun recognizesNaturalResearchRequests() {
        assertTrue(LiveWebSearch.isSearchRequest("Do your research on the 2026 Ballon d'Or top 10"))
        assertTrue(LiveWebSearch.isSearchRequest("Research this app and find it on Play Store"))
        assertTrue(LiveWebSearch.isSearchRequest("Google it"))
    }

    @Test fun removesResearchCommandFromSearchQuery() {
        assertEquals("2026 Ballon d'Or top 10", LiveWebSearch.queryFrom("Do your research 2026 Ballon d'Or top 10"))
    }

    @Test fun searchQueryRemainsBounded() {
        assertTrue(LiveWebSearch.queryFrom("x".repeat(300)).length <= 160)
    }
}
