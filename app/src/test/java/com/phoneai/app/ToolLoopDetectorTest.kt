package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopDetectorTest {
    @Test
    fun warnsAfterRepeatedIdenticalArguments() {
        val detector = ToolLoopDetector()
        var last = LoopCheckResult.NONE
        repeat(10) {
            last = detector.record("read_file", mapOf("path" to "notes.txt"), "same content")
        }
        assertEquals(Level.WARNING, last.level)
        assertTrue(last.message.orEmpty().contains("identical arguments"))
    }

    @Test
    fun blocksRepeatedNoProgressBeforeExecutingAgain() {
        val detector = ToolLoopDetector(
            ToolLoopConfig(
                historySize = 4,
                warningThreshold = 1,
                unknownToolThreshold = 1,
                criticalThreshold = 2,
                globalCircuitBreakerThreshold = 3
            )
        )
        val args = mapOf("path" to "notes.txt")
        repeat(3) { detector.record("read_file", args, "same content") }
        val decision = detector.check("read_file", args)
        assertEquals(Level.CRITICAL, decision.level)
        assertTrue(decision.message.orEmpty().contains("no-progress"))
    }

    @Test
    fun stableArgumentHashIgnoresMapInsertionOrder() {
        val detector = ToolLoopDetector()
        detector.record("tool", linkedMapOf("b" to 2, "a" to 1), "ok")
        val decision = detector.check("tool", linkedMapOf("a" to 1, "b" to 2))
        assertEquals(Level.NONE, decision.level)
    }

    @Test
    fun resetClearsSessionHistory() {
        val detector = ToolLoopDetector()
        repeat(10) { detector.record("tool", mapOf("x" to 1), "same") }
        detector.reset()
        assertEquals(Level.NONE, detector.check("tool", mapOf("x" to 1)).level)
    }
}
