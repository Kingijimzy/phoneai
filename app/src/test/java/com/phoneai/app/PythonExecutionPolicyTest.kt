package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class PythonExecutionPolicyTest {
    @Test fun requiresExplicitApproval() {
        assertEquals("Python execution requires explicit user approval.", PythonExecutionPolicy.rejection("print(1)", false))
        assertNull(PythonExecutionPolicy.rejection("print(1)", true))
    }

    @Test fun rejectsEmptyOversizedAndNulInput() {
        assertNotNull(PythonExecutionPolicy.rejection("  ", true))
        assertNotNull(PythonExecutionPolicy.rejection("x".repeat(PythonExecutionPolicy.MAX_CODE_CHARS + 1), true))
        assertNotNull(PythonExecutionPolicy.rejection("print('x')\u0000", true))
    }
}
