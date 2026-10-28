package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionChatUiStateTest {
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
}
