package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionActivityTimelineTest {
    private fun event(id: String, at: Long, status: OrionActivityStatus = OrionActivityStatus.ACTIVE) =
        OrionActivityEvent(id, "task-1", OrionActivityKind.TOOL, status, "Run tool", "Working", at)

    @Test fun replayOrdersEventsByTimestampAndDeduplicatesStableIds() {
        val timeline = OrionActivityTimeline()
        timeline.replay(listOf(event("later", 200), event("first", 100), event("first", 100)))
        assertEquals(listOf("first", "later"), timeline.snapshot().map { it.eventId })
    }

    @Test fun appendRejectsDuplicateEventsWithoutChangingOriginal() {
        val timeline = OrionActivityTimeline()
        assertTrue(timeline.append(event("same", 100)))
        assertFalse(timeline.append(event("same", 200, OrionActivityStatus.SUCCEEDED)))
        assertEquals(100L, timeline.snapshot().single().timestampMs)
    }

    @Test fun taskFilteringAndRetentionAreBounded() {
        val timeline = OrionActivityTimeline(maxEvents = 2)
        timeline.append(event("a", 1))
        timeline.append(event("b", 2))
        timeline.append(event("c", 3))
        assertEquals(listOf("b", "c"), timeline.snapshot().map { it.eventId })
        assertTrue(timeline.snapshot("another-task").isEmpty())
    }

    @Test fun typedEvidenceAndCorrelationRemainAttachedDuringReplay() {
        val evidence = OrionActivityEvidence.AgentHandoff("research-agent", "parent-1", "Find primary sources", "running")
        val event = OrionActivityEvent(
            eventId = "handoff-1",
            taskId = "task-1",
            kind = OrionActivityKind.DELEGATION,
            status = OrionActivityStatus.ACTIVE,
            title = "Research hand-off",
            summary = "Delegated research",
            timestampMs = 50,
            typedEvidence = listOf(evidence),
            correlationId = "handoff-correlation"
        )
        val timeline = OrionActivityTimeline()
        timeline.replay(listOf(event))
        val restored = timeline.snapshot().single()
        assertEquals("handoff-correlation", restored.correlationId)
        assertEquals("parent-1", (restored.typedEvidence.single() as OrionActivityEvidence.AgentHandoff).parentTaskId)
    }

    @Test fun compactChatProjectionDropsTransientAndDuplicateLifecycleCards() {
        val timeline = OrionActivityTimeline()
        timeline.append(OrionActivityEvent("accepted", "task-1", OrionActivityKind.THINKING,
            OrionActivityStatus.ACTIVE, "Request accepted", "request accepted", 1))
        timeline.append(OrionActivityEvent("tool-start", "task-1", OrionActivityKind.TOOL,
            OrionActivityStatus.ACTIVE, "workspace_read", "Tool: workspace_read", 2, correlationId = "call-1"))
        timeline.append(OrionActivityEvent("tool-result", "task-1", OrionActivityKind.ERROR,
            OrionActivityStatus.FAILED, "workspace_read failed", "tool=workspace_read; outcome=failed", 3, correlationId = "call-1"))
        timeline.append(OrionActivityEvent("finished", "task-1", OrionActivityKind.COMPLETION,
            OrionActivityStatus.SUCCEEDED, "Interaction finished", "interaction processing finished", 4))
        val cards = timeline.compactForChat()
        assertEquals(1, cards.size)
        assertEquals("task-stream-task-1", cards.single().eventId)
        assertEquals(OrionActivityStatus.SUCCEEDED, cards.single().status)
        assertEquals(4, cards.single().evidence.size)
        assertTrue(cards.single().evidence.any { it.contains("FAILED") && it.contains("workspace_read") })
    }

    @Test fun elapsedTimeClampsClockSkewAndFormatsLongRuns() {
        assertEquals("0s", OrionActivityTimeline.elapsedLabel(2_000, 1_000))
        assertEquals("12s", OrionActivityTimeline.elapsedLabel(1_000, 13_000))
        assertEquals("2m5s", OrionActivityTimeline.elapsedLabel(0, 125_000))
        assertEquals("1h2m", OrionActivityTimeline.elapsedLabel(0, 3_720_000))
    }

    @Test fun legacyLifecycleNotesMapToTypedStates() {
        val active = OrionActivityTimeline.fromLegacyNote("task-1", "[TASK:RUNNING]\nRunning browser_search", 10)!!
        assertEquals(OrionActivityKind.TOOL, active.kind)
        assertEquals(OrionActivityStatus.ACTIVE, active.status)
        val approval = OrionActivityTimeline.fromLegacyNote("task-1", "[TASK:APPROVAL]\nConfirm sending", 20)!!
        assertEquals(OrionActivityStatus.WAITING, approval.status)
        val failed = OrionActivityTimeline.fromLegacyNote("task-1", "[SHELL:FAILED]\npython main.py", 30)!!
        assertEquals(OrionActivityKind.CODING, failed.kind)
        assertEquals(OrionActivityStatus.FAILED, failed.status)
        val browserFailed = OrionActivityTimeline.fromLegacyNote("task-1", "[BROWSER:FAILED]\nSearch request", 35)!!
        assertEquals(OrionActivityKind.RESEARCH, browserFailed.kind)
        assertEquals(OrionActivityStatus.FAILED, browserFailed.status)
        val done = OrionActivityTimeline.fromLegacyNote("task-1", "[TASK:COMPLETE]\nVerified output", 40)!!
        assertEquals(OrionActivityStatus.SUCCEEDED, done.status)
        val interrupted = OrionActivityTimeline.fromLegacyNote("task-1", "[TASK:INTERRUPTED]\nService interrupted", 45)!!
        assertEquals(OrionActivityKind.INTERRUPTED, interrupted.kind)
        assertEquals(OrionActivityStatus.INTERRUPTED, interrupted.status)
    }

    @Test fun unrelatedNotesAreNotPresentedAsExecutionEvidence() {
        assertNull(OrionActivityTimeline.fromLegacyNote("task-1", "ordinary assistant note", 10))
    }
}
