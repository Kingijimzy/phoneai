package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrionGeneratedFileAttachmentTest {
    private val block = OrionMessageBlock.GeneratedFile(
        artifactId = "artifact-42",
        fileName = "report.pdf",
        mimeType = "application/pdf",
        sizeBytes = 4096L,
        sha256 = "a".repeat(64),
        state = "available"
    )

    private val attachment = OrionChatAttachmentStore.Attachment(
        id = "artifact-42",
        sessionId = "session-1",
        name = "report.pdf",
        mimeType = "application/pdf",
        sizeBytes = 4096L,
        sha256 = "a".repeat(64),
        relativePath = "chat-attachments/session-1/artifact-report.pdf",
        createdAt = 1L
    )

    @Test fun resolvesOnlyTheExactPersistedArtifact() {
        assertEquals(attachment, resolveGeneratedFileAttachment(block, attachment))
    }

    @Test fun rejectsDifferentArtifactId() {
        assertNull(resolveGeneratedFileAttachment(block, attachment.copy(id = "other-artifact")))
    }

    @Test fun rejectsChangedBytesEvenWhenArtifactIdMatches() {
        assertNull(resolveGeneratedFileAttachment(block, attachment.copy(sha256 = "b".repeat(64))))
    }

    @Test fun rejectsMissingChecksumRatherThanOpeningAnUnverifiedFile() {
        assertNull(resolveGeneratedFileAttachment(block.copy(sha256 = ""), attachment))
    }

    @Test fun rejectsMetadataMismatch() {
        assertNull(resolveGeneratedFileAttachment(block, attachment.copy(sizeBytes = 4095L)))
    }
}
