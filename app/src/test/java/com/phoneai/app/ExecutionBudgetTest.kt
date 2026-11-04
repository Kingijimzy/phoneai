package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ExecutionBudgetTest {
    @Test fun budgetsAreLookedUpByKind() {
        assertEquals(ExecutionBroker.SHELL, ExecutionBroker.forKind("builtin"))
        assertEquals(ExecutionBroker.TERMUX, ExecutionBroker.forKind("Termux"))
        assertEquals(ExecutionBroker.PYTHON, ExecutionBroker.forKind("py"))
        assertEquals(ExecutionBroker.JS, ExecutionBroker.forKind("javascript"))
        assertNull(ExecutionBroker.forKind("ruby"))
    }

    @Test fun timeoutsAreClampedIntoTheBudgetWindow() {
        assertEquals(1L, ExecutionBroker.timeoutSeconds(ExecutionBroker.SHELL, 0L))
        assertEquals(30L, ExecutionBroker.timeoutSeconds(ExecutionBroker.SHELL, 9_999L))
        assertEquals(20L, ExecutionBroker.timeoutSeconds(ExecutionBroker.SHELL, 20L))
    }

    @Test fun oversizedSourceIsRejectedWithTheBudgetName() {
        val tooLong = "x".repeat(ExecutionBroker.JS.maxSourceChars + 1)
        val error = ExecutionBroker.sourceError(ExecutionBroker.JS, tooLong)
        assertNotNull(error)
        assertTrue(error!!.contains("js"))
        assertNull(ExecutionBroker.sourceError(ExecutionBroker.JS, "print(1)"))
    }

    @Test fun outputIsClippedAtTheBudget() {
        val long = "y".repeat(ExecutionBroker.PYTHON.maxOutputChars + 50)
        val clipped = ExecutionBroker.clipOutput(ExecutionBroker.PYTHON, long)
        assertTrue(clipped.length > ExecutionBroker.PYTHON.maxOutputChars)
        assertTrue(clipped.contains("truncated"))
        assertEquals("ok", ExecutionBroker.clipOutput(ExecutionBroker.PYTHON, "ok"))
    }

    @Test fun describeStatesTheNetworkPolicy() {
        assertTrue(ExecutionBroker.describe(ExecutionBroker.PYTHON).contains("network=blocked"))
        assertTrue(ExecutionBroker.describe(ExecutionBroker.SHELL).contains("network=allowed"))
    }
}
