package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionExecutionLoopTest {
    @Test fun stoppedTaskNeverCallsExecutor() {
        var called = false
        val result = OrionExecutionLoop.dispatch("list_apps", stopped = true) {
            called = true
            "unexpected"
        }
        assertFalse(called)
        assertEquals(OrionExecutionLoop.DispatchState.BLOCKED, result.state)
        assertTrue(result.value.startsWith("error:"))
    }

    @Test fun unknownToolNeverCallsExecutor() {
        var called = false
        val result = OrionExecutionLoop.dispatch("invented_tool", stopped = false) {
            called = true
            "success"
        }
        assertFalse(called)
        assertEquals(OrionExecutionLoop.DispatchState.BLOCKED, result.state)
    }

    @Test fun successfulReturnIsDispatchedNotAutomaticallyVerified() {
        val result = OrionExecutionLoop.dispatch("list_apps", stopped = false) {
            "Chrome, Settings"
        }
        assertEquals(OrionExecutionLoop.DispatchState.DISPATCHED, result.state)
        assertFalse(OrionExecutionLoop.completionEvidence(result.value, verificationRequired = true, verificationPassed = false))
        assertTrue(OrionExecutionLoop.completionEvidence(result.value, verificationRequired = false, verificationPassed = false))
    }

    @Test fun executorErrorRemainsFailure() {
        val result = OrionExecutionLoop.dispatch("list_apps", stopped = false) {
            "error: permission denied"
        }
        assertEquals(OrionExecutionLoop.DispatchState.FAILED, result.state)
        assertFalse(OrionExecutionLoop.completionEvidence(result.value, verificationRequired = false, verificationPassed = true))
    }

    @Test fun thrownExceptionBecomesExplicitFailure() {
        val result = OrionExecutionLoop.dispatch("list_apps", stopped = false) {
            throw IllegalStateException("device bridge unavailable")
        }
        assertEquals(OrionExecutionLoop.DispatchState.FAILED, result.state)
        assertTrue(result.value.contains("device bridge unavailable"))
    }

    @Test fun requiredVerificationMustPass() {
        assertFalse(OrionExecutionLoop.completionEvidence("ok", verificationRequired = true, verificationPassed = false))
        assertTrue(OrionExecutionLoop.completionEvidence("ok", verificationRequired = true, verificationPassed = true))
    }
}
