package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionMultimodalEvidenceStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("orion_multimodal_evidence.db")
    }

    @After fun tearDown() {
        context.deleteDatabase("orion_multimodal_evidence.db")
    }

    private fun recordImage(session: String = "session-a"): String =
        OrionMultimodalEvidenceStore.record(
            context = context,
            sessionId = session,
            modality = OrionMultimodalEvidenceStore.Modality.IMAGE,
            source = "test_picker",
            reference = "content://example.provider/images/1",
            summary = "User attached an image"
        )!!

    @Test fun imageCannotBeResolvedWithoutAnExplicitUserRequest() {
        val id = recordImage()
        assertNull(OrionMultimodalEvidenceStore.resolveImageForExplicitRequest(context, "session-a", id, false))
    }

    @Test fun imageEvidenceCannotCrossConversationBoundaries() {
        val id = recordImage()
        assertNull(OrionMultimodalEvidenceStore.resolveImageForExplicitRequest(context, "session-b", id, true))
    }

    @Test fun temporaryOrMissingPersistedGrantFailsClosed() {
        val id = recordImage()
        assertNull(OrionMultimodalEvidenceStore.resolveImageForExplicitRequest(context, "session-a", id, true))
    }

    @Test fun screenEvidenceIsNeverResolvedAsAnImage() {
        val id = OrionMultimodalEvidenceStore.record(
            context = context,
            sessionId = "session-a",
            modality = OrionMultimodalEvidenceStore.Modality.SCREEN,
            source = "screen_observation",
            reference = "content://example.provider/images/1",
            summary = "Screen evidence"
        )!!
        assertNull(OrionMultimodalEvidenceStore.resolveImageForExplicitRequest(context, "session-a", id, true))
    }

    @Test fun evidenceMetadataRemainsAvailableWithoutExposingTheRawUri() {
        val id = recordImage()
        val row = OrionMultimodalEvidenceStore.forSession(context, "session-a").single()
        assertNotNull(id)
        assertEquals(id, row.id)
        assertEquals("session-a", row.sessionId)
        assertFalse(row.toString().contains("content://"))
    }
    @Test fun evidenceMetadataSurvivesDatabaseReopenAndSessionDeletionIsScoped() {
        val first = recordImage("session-a")
        val second = recordImage("session-b")

        // forSession opens and closes its SQLite helper on each read, exercising durable reopen.
        assertEquals(first, OrionMultimodalEvidenceStore.forSession(context, "session-a").single().id)
        assertEquals(second, OrionMultimodalEvidenceStore.forSession(context, "session-b").single().id)

        assertEquals(1, OrionMultimodalEvidenceStore.deleteSession(context, "session-a"))
        assertTrue(OrionMultimodalEvidenceStore.forSession(context, "session-a").isEmpty())
        assertEquals(second, OrionMultimodalEvidenceStore.forSession(context, "session-b").single().id)
    }

}
