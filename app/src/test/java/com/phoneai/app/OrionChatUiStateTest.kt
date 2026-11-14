package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionChatUiStateTest {
    @Test fun workPhasesUseClearHumanProgressLanguage() {
        assertEquals("Thinking through the best approach", orionWorkingPhrase("Planning objective"))
        assertEquals("Fixing the issue", orionWorkingPhrase("Repairing implementation"))
        assertEquals("Verifying that the result works", orionWorkingPhrase("Running verification"))
        assertEquals("Finding the information needed", orionWorkingPhrase("Researching documentation"))
        assertEquals("Recovering and choosing another route", orionWorkingPhrase("Retrying after timeout"))
        assertEquals("Waiting for your approval", orionWorkingPhrase("Approval pending"))
    }

    @Test fun statusMapsToDistinctOrbStatesWithoutOverridingLiveRuntimeState() {
        assertEquals(OrbView.Mode.WAITING_APPROVAL, orionModeForStatus("Approval pending", OrbView.Mode.IDLE))
        assertEquals(OrbView.Mode.ERROR, orionModeForStatus("Execution failed", OrbView.Mode.IDLE))
        assertEquals(OrbView.Mode.ACTING, orionModeForStatus("Executing command", OrbView.Mode.IDLE))
        assertEquals(OrbView.Mode.LISTENING, orionModeForStatus("Listening", OrbView.Mode.IDLE))
        assertEquals(OrbView.Mode.SPEAKING, orionModeForStatus("Speaking", OrbView.Mode.IDLE))
        assertEquals(OrbView.Mode.SPEAKING, orionModeForStatus("Approval pending", OrbView.Mode.SPEAKING))
    }

    @Test fun busyControlsFollowRuntimeModeNotStatusText() {
        assertTrue(isOrionChatBusy(OrbView.Mode.THINKING))
        assertTrue(isOrionChatBusy(OrbView.Mode.SPEAKING))
        assertTrue(isOrionChatBusy(OrbView.Mode.ACTING))
        assertTrue(isOrionChatBusy(OrbView.Mode.WAITING_APPROVAL))
        assertFalse(isOrionChatBusy(OrbView.Mode.IDLE))
        assertFalse(isOrionChatBusy(OrbView.Mode.LISTENING))
        assertFalse(isOrionChatBusy(OrbView.Mode.ERROR))
        assertTrue(isOrionChatBusy(OrbView.Mode.IDLE, taskRunning = true))
    }

    @Test fun coordinatorOwnsMessageAndRuntimeProjection() {
        val state = OrionChatUiState()
        val coordinator = OrionChatCoordinator(state)
        coordinator.onMessage("me", "Hi Orion")
        coordinator.onAssistantDelta("Hello")
        val streamedId = state.lines.last().id
        coordinator.onAssistantDelta(" there")
        coordinator.onMessage("ai", "Hello there!")
        assertEquals(2, state.lines.size)
        assertEquals(streamedId, state.lines.last().id)
        assertEquals("Hello there!", state.lines.last().text)
        assertFalse(state.hasAssistantStream())

        coordinator.onRuntimeStatus("ACTING", OrbView.Mode.ACTING)
        assertEquals("ACTING", state.status.value)
        assertEquals(OrbView.Mode.ACTING, state.orbMode.value)
        assertTrue(state.taskRunning.value)
    }

    @Test fun coordinatorRestoresTranscriptAndClearsStaleSessionProjection() {
        val state = OrionChatUiState()
        val coordinator = OrionChatCoordinator(state)
        state.append(OrionChatLine(1L, "me", "Old session"))
        state.activityEvents.value = emptyList()
        coordinator.restoreTranscript(
            structured = listOf(OrionChatLine(2L, "me", "Restored session")),
            fallback = listOf("me" to "Legacy fallback"),
            events = emptyList()
        )
        assertEquals(1, state.lines.size)
        assertEquals("Restored session", state.lines.single().text)
        assertFalse(state.hasAssistantStream())
        assertTrue(state.activityEvents.value.isEmpty())
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

    @Test fun assistantDeltasUpdateOneStableMessageAndFinalTextReconcilesIt() {
        val state = OrionChatUiState()
        val first = state.appendAssistantDelta("Hello ")
        val second = state.appendAssistantDelta("there")
        assertEquals(first?.id, second?.id)
        assertEquals(1, state.lines.size)
        assertEquals("Hello there", state.lines.single().text)
        assertTrue(state.hasAssistantStream())

        val final = state.completeAssistantStream("Hello there!")
        assertEquals(first?.id, final.id)
        assertEquals(1, state.lines.size)
        assertEquals("Hello there!", state.lines.single().text)
        assertFalse(state.hasAssistantStream())
    }

    @Test fun stoppingAStreamPreservesPartialTextButStartsNextTurnFresh() {
        val state = OrionChatUiState()
        val partial = state.appendAssistantDelta("Partial answer")
        state.resetAssistantStream()
        assertFalse(state.hasAssistantStream())
        assertEquals("Partial answer", state.lines.single().text)

        val next = state.appendAssistantDelta("Next answer")
        assertTrue(partial!!.id != next!!.id)
        assertEquals(2, state.lines.size)
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
        assertFalse(isCompactExecutionNote(OrionChatLine(4, "ai", "I am working on it")))
    }
}
