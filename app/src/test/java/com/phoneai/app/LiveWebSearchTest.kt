package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWebSearchTest {
    @Test fun recognizesNaturalResearchRequests() {
        assertTrue(LiveWebSearch.isSearchRequest("Do your research on the 2026 Ballon d'Or top 10"))
        assertTrue(LiveWebSearch.isSearchRequest("Research this app and find it on Play Store"))
        assertTrue(LiveWebSearch.isSearchRequest("Google it"))
        assertTrue(LiveWebSearch.isLiveInfoRequest("Who won the 2026 Ballon d'Or?"))
        assertTrue(LiveWebSearch.isLiveInfoRequest("Give me the top 10 rankings"))
        assertTrue(LiveWebSearch.isLiveInfoRequest("what the bitcoin news today"))
        assertTrue(LiveWebSearch.isLiveInfoRequest("latest BTC headlines"))
        assertTrue(!LiveWebSearch.isLiveInfoRequest("what is the BTC price right now"))
    }

    @Test fun removesResearchCommandFromSearchQuery() {
        assertEquals("2026 Ballon d'Or top 10", LiveWebSearch.queryFrom("Do your research 2026 Ballon d'Or top 10"))
    }

    @Test fun searchQueryRemainsBounded() {
        assertTrue(LiveWebSearch.queryFrom("x".repeat(300)).length <= 160)
    }
}
