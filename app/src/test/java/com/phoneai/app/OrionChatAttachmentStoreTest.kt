package com.phoneai.app

import android.content.Context
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionChatAttachmentStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("orion_chat_attachments.db")
        context.filesDir.resolve("chat-attachments").deleteRecursively()
    }

    @After fun tearDown() {
        context.deleteDatabase("orion_chat_attachments.db")
        context.filesDir.resolve("chat-attachments").deleteRecursively()
    }

    @Test fun attachmentBytesAndMetadataSurviveStoreReopenAndStayScopedToSession() {
        val source = context.cacheDir.resolve("report.txt")
        source.writeText("Orion durable attachment test")
        val imported = OrionChatAttachmentStore.saveFromUri(
            context, "session-a", Uri.fromFile(source)
        )
        val restored = OrionChatAttachmentStore.forSession(context, "session-a").single()
        assertEquals(imported.id, restored.id)
        assertEquals("report.txt", restored.name)
        assertEquals(source.length(), restored.sizeBytes)
        assertEquals(source.readBytes().toList(), OrionChatAttachmentStore.file(context, restored).readBytes().toList())
        assertTrue(OrionChatAttachmentStore.forSession(context, "session-b").isEmpty())
    }

    @Test fun deletingOneSessionRemovesItsFilesAndMetadataOnly() {
        val a = context.cacheDir.resolve("a.txt").apply { writeText("A") }
        val b = context.cacheDir.resolve("b.txt").apply { writeText("B") }
        OrionChatAttachmentStore.saveFromUri(context, "session-a", Uri.fromFile(a))
        val keep = OrionChatAttachmentStore.saveFromUri(context, "session-b", Uri.fromFile(b))
        assertEquals(1, OrionChatAttachmentStore.deleteSession(context, "session-a"))
        assertTrue(OrionChatAttachmentStore.forSession(context, "session-a").isEmpty())
        assertTrue(OrionChatAttachmentStore.file(context, keep).exists())
        assertEquals(keep.id, OrionChatAttachmentStore.forSession(context, "session-b").single().id)
    }
}
