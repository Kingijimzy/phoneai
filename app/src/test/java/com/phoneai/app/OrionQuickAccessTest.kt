package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionQuickAccessTest {
    @Test fun everyTilePointsAtARealPageOrAnAction() {
        OrionQuickAccess.all().forEach { t ->
            assertTrue("${t.id} needs a page or an action", (t.page != null) xor (t.action != null))
            t.page?.let { assertNotNull("page ${t.page} missing from OrionPages", OrionPages.find(it)) }
        }
    }

    @Test fun tileIdsAreUniqueAndBarIsSubset() {
        val ids = OrionQuickAccess.all().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(6, OrionQuickAccess.bar.size)
        assertTrue(OrionQuickAccess.bar.all { it.id in ids })
    }

    @Test fun mainStudioAndChatToolsAreReachable() {
        val pages = OrionQuickAccess.all().mapNotNull { it.page }
        listOf("studio", "terminal", "workspace", "browser", "tasks", "memory", "mcp", "models", "settings").forEach { assertTrue(it in pages) }
    }
}
