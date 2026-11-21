package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionDeadEndsTest {
    @Test fun differentToolsHittingTheSameWallBecomeADeadEnd() {
        val t = "dead-end-test-" + System.nanoTime()
        assertEquals("", OrionDeadEnds.note(t, "read_file", "error: invalid or missing workspace file /repos/a/b"))
        assertTrue(OrionDeadEnds.note(t, "cat", "error: invalid or missing workspace file /repos/c/d").contains("WARNING"))
        assertTrue(OrionDeadEnds.note(t, "ls", "error: invalid or missing workspace file /x/y").contains("Stop trying routes"))
    }
    @Test fun unrelatedFailuresDoNotAccumulate() {
        val t = "dead-end-test-" + System.nanoTime()
        assertEquals("", OrionDeadEnds.note(t, "a", "error: timeout waiting for the screen to change"))
        assertEquals("", OrionDeadEnds.note(t, "b", "error: no such app installed on this phone"))
    }
}
