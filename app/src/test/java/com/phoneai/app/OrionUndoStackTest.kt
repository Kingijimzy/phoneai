package com.phoneai.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JSONObject
import org.junit.Test
import org.junit.Assert.*

/**
 * Phase 0D: Ultron-Style Undo Stack Tests
 *
 * Tests the undo stack that enables immediate execution with reversible rollback.
 * Embodies the Ultron doctrine: "Act immediately, remember how to reverse it, let the user say undo."
 */
class OrionUndoStackTest {

    private fun createStack(context: Context = ApplicationProvider.getApplicationContext<Context>()): OrionUndoStack {
        return OrionUndoStack(context, PhoneAiServiceStub())
    }

    @Test
    fun `undo stack records and reverses reversible actions`() {
        val stack = createStack()
        var undoCalled = false

        stack.pushUndo("trace1", "save_file (executed)", {
            undoCalled = true
            "File removed: test.txt"
        })

        assertTrue("Should be able to undo", stack.canUndo())
        assertEquals("save_file (executed)", stack.peekUndo())

        val result = stack.undoLast()
        assertTrue("Undo result should confirm reversal", result.contains("Undone:"))
        assertTrue("Undo function should have been called", undoCalled)
        assertFalse("Stack should be empty after undo", stack.canUndo())
    }

    @Test
    fun `undo stack returns empty when nothing to undo`() {
        val stack = createStack()
        assertFalse("Should not be able to undo empty stack", stack.canUndo())
        assertNull("Peek should return null on empty stack", stack.peekUndo())
    }

    @Test
    fun `undo history lists operations most recent first`() {
        val stack = createStack()
        stack.pushUndo("trace1", "first action", { "undone 1" })
        stack.pushUndo("trace2", "second action", { "undone 2" })

        val history = stack.undoHistory()
        assertEquals(2, history.size)
        assertEquals("second action", history[0])
        assertEquals("first action", history[1])
    }

    @Test
    fun `clear removes all undo entries`() {
        val stack = createStack()
        stack.pushUndo("trace1", "action 1", { "undone 1" })
        assertTrue(stack.canUndo())
        stack.clear()
        assertFalse(stack.canUndo())
    }

    @Test
    fun `undo entry function returns result string`() {
        val stack = createStack()
        stack.pushUndo("trace1", "test action", { "custom undo result" })

        val result = stack.undoLast()
        assertTrue(result.contains("custom undo result"))
    }

    // Stub PhoneAiService for testing
    private class PhoneAiServiceStub : PhoneAiService() {
        override fun getApplicationContext(): Context = ApplicationProvider.getApplicationContext()
    }
}
