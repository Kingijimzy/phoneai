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

    @Test fun specialistEventIsIdempotentAndTargetsItsOriginalArchivedSession() {
        val first = listOf("me" to "original specialist request", "ai" to "I will delegate this")
        repository.saveActive(context, first)
        val originId = repository.activeSessionId(context)!!
        repository.archiveAndClear(context, first)

        val second = listOf("me" to "a different active chat", "ai" to "separate conversation")
        repository.saveActive(context, second)
        val activeId = repository.activeSessionId(context)!!
        assertFalse(originId == activeId)

        val firstWrite = repository.recordEventForSession(
            context, originId, "specialist-job-stable-id", "specialist_result", "completed", "Research evidence ready"
        )
        val duplicateWrite = repository.recordEventForSession(
            context, originId, "specialist-job-stable-id", "specialist_result", "completed", "Research evidence ready"
        )
        assertNotNull(firstWrite)
        assertEquals(firstWrite?.id, duplicateWrite?.id)
        assertEquals(activeId, repository.activeSessionId(context))

        assertNotNull(repository.restoreArchivedById(context, originId))
        val events = repository.eventsForActive(context).filter { it.id == "specialist-job-stable-id" }
        assertEquals(1, events.size)
        assertEquals("Research evidence ready", events.single().summary)
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
        val first = repository.searchHistory(context, "keyboard crashes", 5).first()
        repository.saveActive(context, transcript)
        val second = repository.searchHistory(context, "keyboard crashes", 5).first()
        assertEquals(first.sessionId, second.sessionId)
        assertEquals(first.turnId, second.turnId)
        assertEquals("me", second.role)
        assertTrue(second.snippet.contains("keyboard", ignoreCase = true))
    }

    @Test fun fullTextSearchFindsArchivedConversationWithAdjacentTurnContext() {
        val transcript = listOf("me" to "Fix the Telegram delivery timeout", "ai" to "The Telegram bot timed out while sending a reply")
        repository.saveActive(context, transcript)
        val beforeArchive = repository.searchHistory(context, "Telegram delivery", 5).first()
        repository.archiveAndClear(context, transcript)
        val hit = repository.searchHistory(context, "Telegram delivery", 5).first()
        assertEquals(beforeArchive.sessionId, hit.sessionId)
        assertEquals(beforeArchive.turnId, hit.turnId)
        assertTrue(hit.sessionId.isNotBlank())
        assertTrue(hit.turnId.isNotBlank())
        assertTrue(hit.title.contains("Fix the Telegram"))
        assertTrue(hit.after.contains("timed out"))
    }

    @Test fun historicalSearchPrioritizesSpecificEngineeringTermsOverPromptFiller() {
        val transcript = listOf(
            "me" to "We discussed separating backend executors from dispatchLegacyTool; the remaining work was to extract browser, device, MCP, integrations, and skills families.",
            "ai" to "Next, finish the direct-call audit and prove owner settings against actual execution."
        )
        repository.saveActive(context, transcript)
        val query = "Search our previous conversations for the Orion task where we discussed separating backend executors from dispatchLegacyTool. Tell me what was incomplete and what we agreed to do next. Use actual conversation history, not guesses."
        val hits = repository.searchHistory(context, query, 8)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.any { it.snippet.contains("backend executors") || it.snippet.contains("dispatchLegacyTool") })
    }


    @Test fun discussionSearchRanksDenseTechnicalMatchesAboveRecentKeywordMentions() {
        val specific = listOf(
            "me" to "We investigated the Android 16 keyboard crash recovery root cause and agreed to preserve the checkpoint before resuming.",
            "ai" to "The decision was to inspect the in-flight action before retrying."
        )
        repository.saveActive(context, specific)
        repository.archiveAndClear(context, specific)

        val vagueRecent = listOf(
            "me" to "The keyboard crashed after opening Settings.",
            "ai" to "I will look at the crash."
        )
        repository.saveActive(context, vagueRecent)

        val hits = repository.searchHistory(context, "Android 16 keyboard crash recovery root cause", 8)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().title.contains("Android 16 keyboard crash recovery"))
        assertTrue(hits.first().snippet.contains("recovery root cause"))
        assertTrue(hits.first().after.contains("inspect the in-flight action"))
    }

    @Test fun objectiveCompletionRequiresLatestSuccessfulExecutorReceipt() {
        repository.saveActive(context, listOf("me" to "Complete the release objective", "ai" to "I am validating the release"))
        val graph = OrionObjectiveGraph.Graph("Release objective", listOf(OrionObjectiveGraph.Objective("release", "Release the build")))
        assertTrue(OrionObjectiveGraph.save(context, graph))
        val first = OrionObjectiveGraph.recordExecution(context, "release", "build", "Build output confirms success")
        assertNotNull(first)
        val latest = OrionObjectiveGraph.recordExecution(context, "release", "verify_apk", "APK package and signature checks passed")
        assertNotNull(latest)
        assertFalse(OrionObjectiveGraph.completeFromEvidence(context, "release", first!!))
        assertTrue(OrionObjectiveGraph.completeFromEvidence(context, "release", latest!!))
        assertEquals(OrionObjectiveGraph.Status.COMPLETE, OrionObjectiveGraph.load(context)?.nodes?.single()?.status)
    }

    @Test fun objectiveGraphsRemainScopedToTheirConversationSession() {
        val firstTurns = listOf("me" to "First session task", "ai" to "First session response")
        repository.saveActive(context, firstTurns)
        val firstGraph = OrionObjectiveGraph.Graph("First session task", listOf(OrionObjectiveGraph.Objective("first", "First objective")))
        assertTrue(OrionObjectiveGraph.save(context, firstGraph))
        repository.archiveAndClear(context, firstTurns)

        val secondTurns = listOf("me" to "Second session task", "ai" to "Second session response")
        repository.saveActive(context, secondTurns)
        val secondGraph = OrionObjectiveGraph.Graph("Second session task", listOf(OrionObjectiveGraph.Objective("second", "Second objective")))
        assertTrue(OrionObjectiveGraph.save(context, secondGraph))
        assertEquals("Second session task", OrionObjectiveGraph.load(context)?.objective)

        val firstArchive = repository.archived(context).single { it.title == "First session task" }
        assertEquals(firstTurns, repository.restoreArchivedById(context, firstArchive.id))
        assertEquals("First session task", OrionObjectiveGraph.load(context)?.objective)
    }

    @Test fun recentHistoryReturnsTurnReferencesFromTheMostRecentlyUpdatedSession() {
        val older = listOf("me" to "Older unrelated project", "ai" to "Older answer")
        repository.saveActive(context, older)
        repository.archiveAndClear(context, older)
        val latest = listOf("me" to "Repair the Android keyboard crash", "ai" to "The previous crash came from settings resume")
        repository.saveActive(context, latest)
        val turns = repository.recentHistory(context, 8)
        assertEquals(2, turns.size)
        assertTrue(turns.all { it.title.contains("Repair the Android keyboard crash") })
        assertTrue(turns.any { it.role == "me" && it.snippet.contains("keyboard crash") })
        assertTrue(turns.all { it.sessionId == turns.first().sessionId })
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
    @Test fun decisionEvidenceMustMatchStoredSpeakerAndExactTranscriptText() {
        val userText = "I explicitly accept keeping a separate decision ledger with source evidence."
        val assistantText = "I propose a searchable decision history table."
        repository.saveActive(context, listOf("me" to userText, "ai" to assistantText))

        val userEvidence = repository.findTranscriptEvidence(context, "user", userText)
        assertNotNull(userEvidence)
        assertNull(repository.findTranscriptEvidence(context, "assistant", userText))
        assertNotNull(repository.findTranscriptEvidence(context, "assistant", assistantText))
        assertNull(repository.findTranscriptEvidence(context, "user", "I accept a decision that was never said here."))
    }


    @Test fun selfDiagnosticActivityIsPersistedInTheActiveChatTimeline() {
        repository.saveActive(context, listOf("me" to "Orion, self diagnose"))
        val event = repository.recordEvent(
            context, null, "self_diagnostic", "completed",
            "Local self-diagnostic completed: tools=137, structural errors=0"
        )
        assertNotNull(event)
        val saved = repository.eventsForActive(context).single { it.type == "self_diagnostic" }
        assertEquals("completed", saved.status)
        assertTrue(saved.summary.contains("structural errors=0"))
    }
}
