package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrionResearchShortcutLifecycleTest {
    private class FakeCheckpoint : OrionResearchShortcutLifecycle.Checkpoint {
        val events = mutableListOf<String>()
        var allowBegin = true
        var allowRecord = true
        var allowComplete = true
        override fun begin(command: String): Boolean { events += "begin:$command"; return allowBegin }
        override fun beginStep(tool: String, args: JSONObject): Boolean { events += "beginStep:$tool"; return true }
        override fun recordStep(tool: String, args: JSONObject, result: String): Boolean {
            events += "record:$tool:$result"; return allowRecord
        }
        override fun pause() { events += "pause" }
        override fun requireRecovery(reason: String) { events += "recovery:$reason" }
        override fun complete(): Boolean { events += "complete"; return allowComplete }
    }

    @Test fun successfulReadIsCheckpointedBeforeAndAfterDispatch() {
        val checkpoint = FakeCheckpoint()
        val result = OrionResearchShortcutLifecycle.run(
            "check current weather",
            listOf(OrionResearchShortcutLifecycle.Step("weather", JSONObject().put("location", "Lagos"))),
            checkpoint, { false }
        ) { tool, _ -> "Weather result from $tool" }
        assertTrue(result.completed)
        assertEquals(1, result.completedSteps)
        assertEquals(listOf("begin:check current weather", "beginStep:weather", "record:weather:Weather result from weather", "complete"), checkpoint.events)
    }

    @Test fun anExistingUnfinishedTaskPreventsShortcutFromStarting() {
        val checkpoint = FakeCheckpoint().apply { allowBegin = false }
        val result = OrionResearchShortcutLifecycle.run(
            "search for news",
            listOf(OrionResearchShortcutLifecycle.Step("live_news", JSONObject())),
            checkpoint, { false }
        ) { _, _ -> fail("must not dispatch when checkpoint creation fails"); "" }
        assertFalse(result.completed)
        assertEquals(0, result.completedSteps)
        assertEquals(1, checkpoint.events.size)
    }

    @Test fun failedReadIsRecordedAndTaskIsPausedWithoutFalseCompletion() {
        val checkpoint = FakeCheckpoint()
        val result = OrionResearchShortcutLifecycle.run(
            "check the weather",
            listOf(OrionResearchShortcutLifecycle.Step("weather", JSONObject())),
            checkpoint, { false }
        ) { _, _ -> "error: service unavailable" }
        assertFalse(result.completed)
        assertTrue(checkpoint.events.any { it == "record:weather:error: service unavailable" })
        assertEquals("pause", checkpoint.events.last())
        assertFalse(checkpoint.events.contains("complete"))
    }

    @Test fun uncertainEvidenceWriteRequiresRecoveryAndNeverCompletes() {
        val checkpoint = FakeCheckpoint().apply { allowRecord = false }
        val result = OrionResearchShortcutLifecycle.run(
            "research a source",
            listOf(OrionResearchShortcutLifecycle.Step("live_web_search", JSONObject())),
            checkpoint, { false }
        ) { _, _ -> "source results" }
        assertFalse(result.completed)
        assertTrue(checkpoint.events.any { it.startsWith("recovery:") })
        assertFalse(checkpoint.events.contains("complete"))
    }

    @Test fun cancellationStopsAtSafeBoundary() {
        val checkpoint = FakeCheckpoint()
        val result = OrionResearchShortcutLifecycle.run(
            "research two topics",
            listOf(
                OrionResearchShortcutLifecycle.Step("live_news", JSONObject()),
                OrionResearchShortcutLifecycle.Step("live_web_search", JSONObject())
            ),
            checkpoint, { checkpoint.events.any { it.startsWith("record:") } }
        ) { tool, _ -> "read:$tool" }
        assertFalse(result.completed)
        assertEquals(1, result.completedSteps)
        assertEquals("pause", checkpoint.events.last())
        assertFalse(checkpoint.events.any { it == "beginStep:live_web_search" })
    }

    @Test fun failedCompletionMarkerIsReportedAsUnconfirmed() {
        val checkpoint = FakeCheckpoint().apply { allowComplete = false }
        val result = OrionResearchShortcutLifecycle.run(
            "fetch a public source",
            listOf(OrionResearchShortcutLifecycle.Step("fetch_url", JSONObject())),
            checkpoint, { false }
        ) { _, _ -> "source content" }
        assertFalse(result.completed)
        assertTrue(result.output.contains("could not save the completion marker"))
    }
}
