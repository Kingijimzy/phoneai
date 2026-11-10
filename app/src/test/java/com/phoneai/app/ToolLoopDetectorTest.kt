package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopDetectorTest {
    @Test fun warnsAfterRepeatedIdenticalArguments() {
        val detector = ToolLoopDetector()
        var last = LoopCheckResult.NONE
        repeat(10) { last = detector.record("read_file", mapOf("path" to "notes.txt"), "same content") }
        assertEquals(Level.WARNING, last.level)
        assertTrue(last.message.orEmpty().contains("identical arguments"))
    }

    @Test fun blocksRepeatedNoProgressBeforeExecutingAgain() {
        val detector = ToolLoopDetector(ToolLoopConfig(
            historySize = 4, warningThreshold = 1, unknownToolThreshold = 1,
            criticalThreshold = 2, globalCircuitBreakerThreshold = 3
        ))
        val args = mapOf("path" to "notes.txt")
        repeat(3) { detector.record("read_file", args, "same content") }
        val decision = detector.check("read_file", args)
        assertEquals(Level.CRITICAL, decision.level)
        assertTrue(decision.message.orEmpty().contains("no-progress"))
    }

    @Test fun stableArgumentHashIgnoresMapInsertionOrder() {
        val detector = ToolLoopDetector()
        detector.record("tool", linkedMapOf("b" to 2, "a" to 1), "ok")
        assertEquals(Level.NONE, detector.check("tool", linkedMapOf("a" to 1, "b" to 2)).level)
    }

    @Test fun resetClearsSessionHistory() {
        val detector = ToolLoopDetector()
        repeat(10) { detector.record("tool", mapOf("x" to 1), "same") }
        detector.reset()
        assertEquals(Level.NONE, detector.check("tool", mapOf("x" to 1)).level)
    }
    @Test fun stopsUnchangedTaskListAfterThreeExecutions() {
        val detector = ToolLoopDetector()
        val args = mapOf("objective_id" to "main")
        detector.record("task_list", args, "same task snapshot")
        assertEquals(Level.NONE, detector.check("task_list", args).level)

        detector.record("task_list", args, "same task snapshot")
        val warning = detector.check("task_list", args)
        assertEquals(Level.WARNING, warning.level)
        assertTrue(warning.message.orEmpty().contains("no progress"))

        detector.record("task_list", args, "same task snapshot")
        val blocked = detector.check("task_list", args)
        assertEquals(Level.CRITICAL, blocked.level)
        assertTrue(blocked.message.orEmpty().contains("task_list"))
    }

    @Test fun changedTaskListSnapshotResetsNoProgressStreak() {
        val detector = ToolLoopDetector()
        val args = mapOf("objective_id" to "main")
        detector.record("task_list", args, "queued; recorded_steps=2")
        detector.record("task_list", args, "running; recorded_steps=3")
        assertEquals(Level.NONE, detector.check("task_list", args).level)
    }

}