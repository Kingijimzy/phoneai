package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the real SQLite repository, not a mocked session policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionConversationRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: OrionConversationRepository

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        resetRepositorySingleton()
        context.deleteDatabase("orion_conversations.db")
        context.getSharedPreferences("orion_chat_history", Context.MODE_PRIVATE).edit().clear().commit()
        repository = OrionConversationRepository.get(context)
    }

    @After fun tearDown() {
        repository.close()
        resetRepositorySingleton()
    }

    @Test fun switchingSessionsRestoresTheExactArchivedSessionById() {
        val first = listOf("me" to "first question", "ai" to "first answer")
        repository.saveActive(context, first)
        repository.archiveAndClear(context, first)

        val second = listOf("me" to "second question", "ai" to "second answer")
        repository.saveActive(context, second)
        val archives = repository.archived(context)
        val firstArchive = archives.single { it.title == "first question" }
        assertEquals(first, firstArchive.messages)

        val restored = repository.restoreArchivedById(context, firstArchive.id)
        assertEquals(first, restored)
        assertEquals(first, repository.loadActive(context))
        assertTrue(repository.archived(context).any { it.title == "second question" && it.messages == second })
    }

    @Test fun deletingArchivedSessionByIdDoesNotDeleteOtherSessionsOrActiveChat() {
        val first = listOf("me" to "keep me", "ai" to "answer one")
        repository.saveActive(context, first)
        repository.archiveAndClear(context, first)
        val second = listOf("me" to "delete me", "ai" to "answer two")
        repository.saveActive(context, second)
        repository.archiveAndClear(context, second)
        val active = listOf("me" to "active stays", "ai" to "active answer")
        repository.saveActive(context, active)

        val deleteTarget = repository.archived(context).single { it.title == "delete me" }
        assertTrue(repository.deleteArchivedById(context, deleteTarget.id))
        assertFalse(repository.archived(context).any { it.id == deleteTarget.id })
        assertTrue(repository.archived(context).any { it.title == "keep me" })
        assertEquals(active, repository.loadActive(context))
        assertFalse(repository.deleteArchivedById(context, deleteTarget.id))
    }

    @Test fun savedActiveConversationSurvivesRepositoryRecreationLikeProcessRestart() {
        val saved = listOf("me" to "persist this", "ai" to "persisted answer")
        repository.saveActive(context, saved)
        repository.close()
        resetRepositorySingleton()

        // New helper instance reopens the same on-disk SQLite database and preferences.
        repository = OrionConversationRepository.get(context)
        assertEquals(saved, repository.loadActive(context))
        assertNotNull(context.getSharedPreferences("orion_chat_history", Context.MODE_PRIVATE)
            .getString("active_session_id", null))
    }

    @Test fun restoringUnknownOrAlreadyActiveSessionDoesNotChangeActiveTranscript() {
        val active = listOf("me" to "current", "ai" to "current answer")
        repository.saveActive(context, active)
        assertNull(repository.restoreArchivedById(context, "missing-session-id"))
        assertEquals(active, repository.loadActive(context))
    }

    @Test fun migratesRealLegacyPreferencesOnceAndPreservesActiveAndArchivedChats() {
        val prefs = context.getSharedPreferences("orion_chat_history", Context.MODE_PRIVATE)
        val legacyActive = org.json.JSONArray()
            .put(org.json.JSONObject().put("kind", "me").put("text", "legacy active question"))
            .put(org.json.JSONObject().put("kind", "ai").put("text", "legacy active answer"))
        val legacyArchivedMessages = org.json.JSONArray()
            .put(org.json.JSONObject().put("kind", "me").put("text", "legacy archived question"))
            .put(org.json.JSONObject().put("kind", "ai").put("text", "legacy archived answer"))
        val legacyArchives = org.json.JSONArray().put(
            org.json.JSONObject()
                .put("sessionId", "legacy-session-test")
                .put("timestamp", 123456L)
                .put("title", "legacy archive")
                .put("messages", legacyArchivedMessages)
        )
        prefs.edit()
            .putString("active_transcript", legacyActive.toString())
            .putString("archived_chats", legacyArchives.toString())
            .commit()

        val activeExpected = listOf("me" to "legacy active question", "ai" to "legacy active answer")
        val archivedExpected = listOf("me" to "legacy archived question", "ai" to "legacy archived answer")
        assertEquals(activeExpected, repository.loadActive(context))
        assertTrue(repository.archived(context).any {
            it.id == "legacy-session-test" && it.messages == archivedExpected
        })
        assertTrue(prefs.getBoolean("sqlite_sessions_migrated_v1", false))

        // Once the migration commits, stale legacy preferences must not overwrite SQLite state.
        prefs.edit().putString(
            "active_transcript",
            org.json.JSONArray().put(org.json.JSONObject().put("kind", "me").put("text", "stale legacy data")).toString()
        ).commit()
        assertEquals(activeExpected, repository.loadActive(context))
    }

    @Test fun persistedLifecycleEventRedactsCredentialLikeValues() {
        val event = repository.recordEvent(
            context,
            messageId = "message-1",
            type = "tool_result",
            status = "success",
            summary = "api_key=sk-1234567890abcdef Bearer abcdefghijklmnop password=hunter2"
        )
        assertNotNull(event)
        val safeSummary = event!!.summary
        assertFalse(safeSummary.contains("sk-1234567890abcdef"))
        assertFalse(safeSummary.contains("abcdefghijklmnop"))
        assertFalse(safeSummary.contains("hunter2"))
        assertTrue(safeSummary.contains("[redacted]"))
    }

    @Test fun lifecycleEventsFollowTheirSessionAcrossSwitchAndDeletion() {
        val first = listOf("me" to "first task", "ai" to "first result")
        repository.saveActive(context, first)
        val firstEvent = repository.recordEvent(
            context, messageId = "first-message", type = "tool_result",
            status = "success", summary = "first session tool completed"
        )
        assertNotNull(firstEvent)
        repository.archiveAndClear(context, first)

        val second = listOf("me" to "second task", "ai" to "second result")
        repository.saveActive(context, second)
        val secondEvent = repository.recordEvent(
            context, messageId = "second-message", type = "tool_result",
            status = "failed", summary = "second session tool failed"
        )
        assertNotNull(secondEvent)
        assertEquals(listOf(secondEvent!!.id), repository.eventsForActive(context).map { it.id })

        val firstArchive = repository.archived(context).single { it.title == "first task" }
        assertEquals(first, repository.restoreArchivedById(context, firstArchive.id))
        assertEquals(listOf(firstEvent!!.id), repository.eventsForActive(context).map { it.id })

        assertTrue(repository.deleteArchivedById(context, secondEvent.sessionId))
        assertEquals(listOf(firstEvent.id), repository.eventsForActive(context).map { it.id })
    }

    @Test fun fullTextSearchReturnsStableSessionAndTurnReferencesAcrossSaves() {
        val transcript = listOf("me" to "The keyboard crashes after switching apps", "ai" to "I will inspect the keyboard crash and recovery path")
        repository.saveActive(context, transcript)
        val first = repository.searchHistory(context, "keyboard crashes", 5).single()
        repository.saveActive(context, transcript)
        val second = repository.searchHistory(context, "keyboard crashes", 5).single()
        assertEquals(first.sessionId, second.sessionId)
        assertEquals(first.turnId, second.turnId)
        assertEquals("me", second.role)
        assertTrue(second.snippet.contains("keyboard", ignoreCase = true))
    }

    @Test fun fullTextSearchFindsArchivedConversationWithAdjacentTurnContext() {
        val transcript = listOf("me" to "Fix the Telegram delivery timeout", "ai" to "The Telegram bot timed out while sending a reply")
        repository.saveActive(context, transcript)
        repository.archiveAndClear(context, transcript)
        val hit = repository.searchHistory(context, "Telegram delivery", 5).single()
        assertTrue(hit.sessionId.isNotBlank())
        assertTrue(hit.turnId.isNotBlank())
        assertTrue(hit.title.contains("Fix the Telegram"))
        assertTrue(hit.after.contains("timed out"))
    }

    @Test fun objectiveEvidenceIsBoundToActiveSessionAndLatestUserTurn() {
        repository.saveActive(context, listOf("me" to "Finish the conversation index", "ai" to "I will add indexed recall"))
        val evidence = repository.recordObjectiveEvidence(context, "history-index", "web_search", "success", "Search returned source-backed documentation")
        assertNotNull(evidence)
        assertEquals("history-index", evidence!!.objectiveId)
        assertNotNull(evidence.turnId)
        assertEquals(evidence.sessionId, repository.searchHistory(context, "conversation index", 1).single().sessionId)
        assertEquals(evidence.id, repository.objectiveEvidence(context, "history-index").single().id)
    }

    private fun resetRepositorySingleton() {
        val field = OrionConversationRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }
}
