package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrionNativeToolDispatcherTest {
    private fun call(id: String, name: String, args: JSONObject = JSONObject(), available: Boolean = true, validationError: String? = null) =
        OrionModelToolCall(id, name, args, available, validationError)

    @Test fun dispatchesMultipleRegisteredCallsInOrder() {
        val executed = mutableListOf<String>()
        val outcomes = OrionNativeToolDispatcher.dispatch(
            listOf(call("1", "media_status"), call("2", "device_operation", JSONObject().put("operation", "read_battery"))),
            cancelled = { false }, isRegistered = { true },
            execute = { name, _ -> executed += name; "result:$name" }
        )
        assertEquals(listOf("media_status", "device_operation"), executed)
        assertEquals(listOf("result:media_status", "result:device_operation"), outcomes.map { it.content })
        assertTrue(outcomes.all { it.dispatched })
    }

    @Test fun malformedAndUnavailableCallsNeverReachExecutor() {
        var executions = 0
        val outcomes = OrionNativeToolDispatcher.dispatch(
            listOf(call("bad", "python_exec", validationError = "arguments must be an object"),
                call("missing", "not_registered", available = false)),
            cancelled = { false }, isRegistered = { false },
            execute = { _, _ -> executions++; "should not run" }
        )
        assertEquals(0, executions)
        assertFalse(outcomes[0].dispatched)
        assertTrue(outcomes[0].content.contains("malformed arguments"))
        assertFalse(outcomes[1].dispatched)
        assertTrue(outcomes[1].content.contains("not registered"))
    }

    @Test fun schemaInvalidArgumentsAreRejectedBeforeExecutor() {
        var executions = 0
        val outcomes = OrionNativeToolDispatcher.dispatch(
            listOf(call("1", "media_status", JSONObject().put("unexpected", true))),
            cancelled = { false }, isRegistered = { true },
            execute = { _, _ -> executions++; "must not run" }
        )
        assertEquals(0, executions)
        assertFalse(outcomes.single().dispatched)
        assertTrue(outcomes.single().content.contains("unknown argument"))
    }

    @Test fun deniedPermissionResultIsReturnedWithoutBypassingGuard() {
        val outcomes = OrionNativeToolDispatcher.dispatch(
            listOf(call("1", "media_status")),
            cancelled = { false }, isRegistered = { true },
            execute = { _, _ -> "error: Android permission is denied; owner approval is required." }
        )
        assertTrue(outcomes.single().dispatched)
        assertTrue(outcomes.single().content.contains("owner approval is required"))
    }

    @Test fun cancellationStopsLaterCallsAndReturnsExplicitNoDispatchResults() {
        var cancelled = false
        var executions = 0
        val outcomes = OrionNativeToolDispatcher.dispatch(
            listOf(call("1", "media_status"), call("2", "device_operation", JSONObject().put("operation", "read_battery"))),
            cancelled = { cancelled }, isRegistered = { true },
            execute = { _, _ -> executions++; cancelled = true; "first result" }
        )
        assertEquals(1, executions)
        assertEquals("first result", outcomes[0].content)
        assertTrue(outcomes[1].content.contains("cancelled before dispatch"))
        assertFalse(outcomes[1].dispatched)
    }

    @Test fun executionFailureIsPreservedAsFeedbackForTheNextModelTurn() {
        val outcomes = OrionNativeToolDispatcher.dispatch(
            listOf(call("1", "media_status")),
            cancelled = { false }, isRegistered = { true },
            execute = { _, _ -> "error: temporary service failure" }
        )
        assertEquals("error: temporary service failure", outcomes.single().content)
        assertTrue(outcomes.single().dispatched)
    }
    @Test fun callbackRejectsInvalidArgumentsBeforeExecutor() {
        var executions = 0
        val result = OrionNativeToolDispatcher.dispatchCallback(
            name = "media_status",
            args = JSONObject().put("unexpected", true),
            cancelled = { false },
            isRegistered = { true },
            execute = { _, _ -> executions++; "must not run" }
        )
        assertEquals(0, executions)
        assertTrue(result.contains("unknown argument"))
    }

    @Test fun callbackUsesTheSameContractAndPreservesExecutorFeedback() {
        var received: JSONObject? = null
        val result = OrionNativeToolDispatcher.dispatchCallback(
            name = "device_operation",
            args = JSONObject().put("operation", "read_battery"),
            cancelled = { false },
            isRegistered = { it == "device_operation" },
            execute = { _, args -> received = args; "battery read" }
        )
        assertEquals("read_battery", received?.optString("operation"))
        assertEquals("battery read", result)
    }

    @Test fun callbackRejectsUnregisteredAndCancelledCalls() {
        var executions = 0
        val unavailable = OrionNativeToolDispatcher.dispatchCallback(
            "not_registered", JSONObject(), { false }, { false },
            { _, _ -> executions++; "must not run" }
        )
        val cancelled = OrionNativeToolDispatcher.dispatchCallback(
            "media_status", JSONObject(), { true }, { true },
            { _, _ -> executions++; "must not run" }
        )
        assertTrue(unavailable.contains("not registered"))
        assertTrue(cancelled.contains("cancelled"))
        assertEquals(0, executions)
    }

}
