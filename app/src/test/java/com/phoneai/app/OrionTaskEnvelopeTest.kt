package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionTaskEnvelopeTest {
    @Test fun generatedEnvelopeIsValidAndRoundTripsWithoutLosingLineage() {
        val now = 1_800_000_000_000L
        val parent = OrionTaskEnvelope.create("Plan the work", now = now)
        val child = OrionTaskEnvelope.create(
            "Research one subtask", kind = OrionTaskEnvelope.Kind.RESEARCH,
            parentTaskId = parent.taskId, rootTaskId = parent.rootTaskId,
            capabilities = listOf("research_web", "read_screen"),
            evidenceRefs = listOf("evidence:123"), now = now
        )
        assertTrue(child.validate(now).isEmpty())
        val restored = OrionTaskEnvelope.fromJson(child.toJson())
        assertEquals(child, restored)
        assertEquals(parent.taskId, restored.parentTaskId)
        assertEquals(parent.rootTaskId, restored.rootTaskId)
    }

    @Test fun rejectsExpiredDeadlineAndMalformedTraceContext() {
        val now = 1_800_000_000_000L
        val valid = OrionTaskEnvelope.create("Verify a result", deadlineAt = now + 10_000, now = now)
        val invalid = valid.copy(deadlineAt = now - 1, traceId = "not-a-trace")
        val errors = invalid.validate(now)
        assertTrue(errors.contains("expired_or_invalid_deadline"))
        assertTrue(errors.contains("invalid_trace_context"))
    }

    @Test fun rejectsUnknownSchemaAndOversizedCapabilityLists() {
        val now = 1_800_000_000_000L
        val valid = OrionTaskEnvelope.create("Check a task", now = now)
        val invalid = valid.copy(schemaVersion = 99, requestedCapabilities = (1..30).map { "tool_$it" })
        val errors = invalid.validate(now)
        assertTrue(errors.contains("unsupported_schema_version"))
        assertTrue(errors.contains("invalid_capabilities"))
    }

    @Test fun parserRejectsUnknownTaskKinds() {
        val json = OrionTaskEnvelope.create("Inspect", now = 1_800_000_000_000L).toJson()
            .put("kind", "ARBITRARY_COMMAND")
        assertFalse(runCatching { OrionTaskEnvelope.fromJson(JSONObject(json.toString())) }.isSuccess)
    }
}
