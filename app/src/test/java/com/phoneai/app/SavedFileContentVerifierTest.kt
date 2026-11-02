package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedFileContentVerifierTest {
    @Test fun acceptsExactReadBackBytes() {
        assertTrue(SavedFileContentVerifier.matches("hello Orion".toByteArray(), "hello Orion".toByteArray()))
    }

    @Test fun rejectsTruncatedContent() {
        assertFalse(SavedFileContentVerifier.matches("hello Orion".toByteArray(), "hello".toByteArray()))
    }

    @Test fun rejectsChangedContent() {
        assertFalse(SavedFileContentVerifier.matches("hello Orion".toByteArray(), "hello orion".toByteArray()))
    }

    @Test fun rejectsUnexpectedTrailingBytes() {
        assertFalse(SavedFileContentVerifier.matches("hello".toByteArray(), "hello!".toByteArray()))
    }
}
