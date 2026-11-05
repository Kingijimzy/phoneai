package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionSessionSnapshotTest {
    @Test fun rendersSessionIdentityAndSourceLoadReceipt() {
        val snapshot = OrionSessionSnapshot(
            request = "continue the existing task",
            sessionId = "session-123",
            capturedAtMillis = 1234L,
            sections = listOf("MEMORY: approved preference", "TASK: existing objective"),
            sourceLoadDiagnostics = listOf("memory=loaded", "checkpoint=loaded")
        )
        val rendered = snapshot.render(2_000)
        assertTrue(rendered.contains("session_id=session-123"))
        assertTrue(rendered.contains("MEMORY: approved preference"))
        assertTrue(rendered.contains("memory=loaded"))
        assertTrue(rendered.contains("CENTRAL BRAIN SESSION SNAPSHOT"))
    }

    @Test fun blankSectionsAreNotInjectedAndBudgetIsBounded() {
        val snapshot = OrionSessionSnapshot(
            request = "question",
            sessionId = "",
            capturedAtMillis = 1L,
            sections = listOf("", "   ", "ONLY_VALID_SECTION"),
            sourceLoadDiagnostics = emptyList()
        )
        val rendered = snapshot.render(256)
        assertTrue(rendered.contains("ONLY_VALID_SECTION"))
        assertFalse(rendered.contains("sections_loaded=3"))
    }
}
