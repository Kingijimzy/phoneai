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

    private fun resetRepositorySingleton() {
        val field = OrionConversationRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }
}
