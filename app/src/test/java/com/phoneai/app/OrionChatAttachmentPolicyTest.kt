package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OrionChatAttachmentPolicyTest {
    private fun att(id: String, name: String, mime: String, sha: String = id) =
        OrionChatAttachmentStore.Attachment(id, "s", name, mime, 10, sha, "p/$name", 1L)

    @Test fun imagesAreNotListedAgainBecauseTheBubbleShowsThem() {
        val list = listOf(att("1", "shot.jpg", "image/jpeg"), att("2", "notes.txt", "text/plain"), att("3", "x.png", "application/octet-stream"))
        assertEquals(listOf("2"), OrionChatAttachmentPolicy.listed(list, emptySet()).map { it.id })
    }

    @Test fun duplicatesAndInlineFilesAreDropped() {
        val list = listOf(att("1", "a.txt", "text/plain", "same"), att("2", "a-copy.txt", "text/plain", "same"), att("3", "b.txt", "text/plain"))
        assertEquals(listOf("1"), OrionChatAttachmentPolicy.listed(list, setOf("3")).map { it.id })
    }
}
