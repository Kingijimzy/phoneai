package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionChatUiStateTest {
    @Test fun workStatusIsTheRealStatusNotAStockPhrase() {
        assertEquals("Running verification", orionWorkingPhrase("running verification"))
        assertEquals("Waiting for your approval", orionWorkingPhrase("Approval pending"))
        assertEquals("…", orionWorkingPhrase("  "))
    }

    @Test fun statusMapsToDistinctOrbStatesWithoutOverridingLiveRuntimeState() {
        assertEquals(OrbView.Mode.WAITING_APPROVAL, orionModeForStatus("Approval pending", OrbView.Mode.IDLE))
        assertEquals(OrbView.Mode.ERROR, orionModeForStatus("Execution failed", OrbView.Mode.IDLE))
        assertEquals(OrbView.Mode.ACTING, orionModeForStatus("Executing command", OrbView.Mode.IDLE))
        assertEquals(OrbView.Mode.LISTENING, orionModeForStatus("Listening", OrbView.Mode.IDLE))
        assertEquals(OrbView.Mode.SPEAKING, orionModeForStatus("Speaking", OrbView.Mode.IDLE))
        assertEquals(OrbView.Mode.SPEAKING, orionModeForStatus("Approval pending", OrbView.Mode.SPEAKING))
    }

    @Test fun historicalActivityCannotMakeAnIdleComposerLookBusy() {
        assertFalse(orionChatRuntimeBusy(taskRunning = false, mode = OrbView.Mode.IDLE))
    }

    @Test fun liveTaskAndLiveExecutionModesKeepComposerStopAvailable() {
        assertTrue(orionChatRuntimeBusy(taskRunning = true, mode = OrbView.Mode.IDLE))
        assertTrue(orionChatRuntimeBusy(taskRunning = false, mode = OrbView.Mode.THINKING))
        assertTrue(orionChatRuntimeBusy(taskRunning = false, mode = OrbView.Mode.ACTING))
        assertFalse(orionChatRuntimeBusy(taskRunning = false, mode = OrbView.Mode.SPEAKING))
    }

    @Test fun startsWithSafeChatDefaults() {
        val state = OrionChatUiState()
        assertTrue(state.lines.isEmpty())
        assertEquals("Choose provider", state.providerLabel.value)
        assertEquals("Choose model", state.modelLabel.value)
        assertFalse(state.providerReady.value)
        assertEquals("READY", state.status.value)
        assertEquals("Orion", state.chatTitle.value)
        assertFalse(state.commandMode.value)
        assertTrue(state.selectedImages.value.isEmpty())
    }

    @Test fun providerAndModelUpdatesStayInUiState() {
        val state = OrionChatUiState()
        state.setProvider("Gemini", true)
        state.setModel("Flash")
        assertEquals("Gemini", state.providerLabel.value)
        assertTrue(state.providerReady.value)
        assertEquals("Flash", state.modelLabel.value)
    }

    @Test fun titleIsBoundedAndBlankTitlesFallBack() {
        val state = OrionChatUiState()
        state.setTitle("x".repeat(80))
        assertEquals(48, state.chatTitle.value.length)
        state.setTitle("   ")
        assertEquals("Orion", state.chatTitle.value)
    }

    @Test fun modeStatusImageAndTranscriptCanBeUpdated() {
        val state = OrionChatUiState()
        state.setStatus("THINKING")
        state.setCommandMode(true)
        state.append(OrionChatLine(1L, "me", "hello"))
        assertEquals("THINKING", state.status.value)
        assertTrue(state.commandMode.value)
        assertEquals(1, state.lines.size)
        state.clearTranscript()
        assertTrue(state.lines.isEmpty())
    }

    @Test fun executionNotesAreFoldedIntoTheLiveStreamButNormalNotesRemainVisible() {
        assertTrue(isCompactExecutionNote(OrionChatLine(1, "note", "[TASK:RUNNING]\\nRunning python_exec")))
        assertTrue(isCompactExecutionNote(OrionChatLine(2, "note", "[TOOL_RESULT:FAILED]\\npython_exec")))
        assertFalse(isCompactExecutionNote(OrionChatLine(3, "note", "[TASK:WARNING]\\nApproval configuration needs review")))
        assertTrue(isCompactExecutionNote(OrionChatLine(5, "note", "[TASK:WARNING]\\nWARNING: may modify data. Orion is continuing because you requested execution.")))
        assertFalse(isCompactExecutionNote(OrionChatLine(4, "ai", "I am working on it")))
    }
}
