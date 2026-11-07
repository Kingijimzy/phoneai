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
            rootObjective = parent.rootObjective,
            capabilities = listOf("research_web", "read_screen"),
            evidenceRefs = listOf("evidence:123"), now = now
        )
        assertTrue(child.validate(now).isEmpty())
        val restored = OrionTaskEnvelope.fromJson(child.toJson())
        assertEquals(child, restored)
        assertEquals(parent.taskId, restored.parentTaskId)
        assertEquals(parent.rootTaskId, restored.rootTaskId)
        assertEquals(parent.objective, restored.rootObjective)
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

    @Test fun descendantsKeepTheOriginalRootObjectiveAcrossMultipleLevels() {
        val now = 1_800_000_000_000L
        val root = OrionTaskEnvelope.create("Complete the Orion adoption", now = now)
        val child = OrionTaskEnvelope.create(
            "Implement Cerebro retrieval", kind = OrionTaskEnvelope.Kind.DELEGATED_STEP,
            parentTaskId = root.taskId, rootTaskId = root.rootTaskId,
            rootObjective = root.rootObjective, now = now
        )
        val grandchild = OrionTaskEnvelope.create(
            "Add graph retrieval tests", kind = OrionTaskEnvelope.Kind.DELEGATED_STEP,
            parentTaskId = child.taskId, rootTaskId = child.rootTaskId,
            rootObjective = child.rootObjective, now = now
        )
        assertEquals(root.objective, child.rootObjective)
        assertEquals(root.objective, grandchild.rootObjective)
        assertEquals(root.objective, OrionTaskEnvelope.fromJson(grandchild.toJson()).rootObjective)
        assertTrue(grandchild.validate(now).isEmpty())
    }

    @Test fun taskEnvelopeRedactsSecretsFromBothRootAndChildObjectives() {
        val task = OrionTaskEnvelope.create(
            "Research Android docs api_key=child-secret",
            rootObjective = "Complete setup with access_token=root-secret",
            now = 1_800_000_000_000L
        )
        val json = task.toJson().toString()
        assertFalse(json.contains("child-secret"))
        assertFalse(json.contains("root-secret"))
        assertTrue(json.contains("[redacted]"))
    }
}
