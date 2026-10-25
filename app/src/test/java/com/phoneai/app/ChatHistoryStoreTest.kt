package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatHistoryStoreTest {
    private fun entry(timestamp: Long, title: String) =
        JSONObject().put("timestamp", timestamp).put("title", title).put("messages", JSONArray())

    @Test fun deletingArchivedEntryPreservesOtherConversations() {
        val raw = JSONArray().put(entry(10L, "first")).put(entry(20L, "second")).put(entry(30L, "third")).toString()

        val updated = ChatHistoryStore.removeArchivedEntry(raw, 20L)

        val result = JSONArray(updated)
        assertEquals(2, result.length())
        assertEquals("first", result.getJSONObject(0).getString("title"))
        assertEquals("third", result.getJSONObject(1).getString("title"))
    }

    @Test fun deletingUnknownTimestampDoesNotChangeArchive() {
        val raw = JSONArray().put(entry(10L, "first")).toString()

        assertNull(ChatHistoryStore.removeArchivedEntry(raw, 99L))
    }

    @Test fun malformedArchiveDoesNotPretendDeletionSucceeded() {
        assertNull(ChatHistoryStore.removeArchivedEntry("not-json", 10L))
    }
}
