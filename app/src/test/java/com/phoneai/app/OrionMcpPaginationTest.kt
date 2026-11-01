package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrionMcpPaginationTest {
    @Test fun accumulatesPagesAndPassesOpaqueCursorForward() {
        val requested = mutableListOf<String?>()
        val result = OrionMcpPagination.collect("resources", maxPages = 4) { cursor ->
            requested += cursor
            when (cursor) {
                null -> JSONObject().put("resources", JSONArray().put(JSONObject().put("uri", "a"))).put("nextCursor", "opaque-1")
                "opaque-1" -> JSONObject().put("resources", JSONArray().put(JSONObject().put("uri", "b")))
                else -> error("unexpected cursor")
            }
        }
        assertEquals(listOf(null, "opaque-1"), requested)
        assertEquals(2, result.items.length())
        assertEquals(2, result.pages)
        assertFalse(result.truncated)
    }

    @Test fun stopsAtPageAndItemBoundsAndMarksPartialResults() {
        val pageLimited = OrionMcpPagination.collect("prompts", maxPages = 2) { cursor ->
            JSONObject().put("prompts", JSONArray().put(JSONObject().put("name", cursor ?: "first")))
                .put("nextCursor", if (cursor == null) "next" else "last")
        }
        assertEquals(2, pageLimited.pages)
        assertTrue(pageLimited.truncated)

        val itemLimited = OrionMcpPagination.collect("tools", maxPages = 3, maxItems = 2) {
            JSONObject().put("tools", JSONArray().put(JSONObject()).put(JSONObject()).put(JSONObject()).put("nextCursor", "more"))
        }
        assertEquals(2, itemLimited.items.length())
        assertTrue(itemLimited.truncated)
    }

    @Test fun rejectsCursorCyclesAndMalformedServerCursors() {
        assertThrows(IllegalStateException::class.java) {
            OrionMcpPagination.collect("resources") { cursor ->
                JSONObject().put("resources", JSONArray().put(JSONObject()))
                    .put("nextCursor", if (cursor == null) "repeat" else "repeat")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrionMcpPagination.collect("prompts") {
                JSONObject().put("prompts", JSONArray()).put("nextCursor", "bad\nvalue")
            }
        }
    }
}
