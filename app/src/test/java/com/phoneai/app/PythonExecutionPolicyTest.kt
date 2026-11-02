package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class PythonExecutionPolicyTest {
    @Test fun supportedSandboxCodeNeedsNoInteractiveApproval() {
        assertNull(PythonExecutionPolicy.rejection("print(1)"))
    }

    @Test fun developerProfileRaisesOnlyTheBoundedSizeLimit() {
        assertNull(PythonExecutionPolicy.rejection("x".repeat(9_000), PythonExecutionPolicy.DEVELOPER_MAX_CODE_CHARS))
        assertNotNull(PythonExecutionPolicy.rejection("x".repeat(17_000), PythonExecutionPolicy.DEVELOPER_MAX_CODE_CHARS))
    }

    @Test fun rejectsEmptyOversizedAndNulInput() {
        assertNotNull(PythonExecutionPolicy.rejection("  "))
        assertNotNull(PythonExecutionPolicy.rejection("x".repeat(PythonExecutionPolicy.MAX_CODE_CHARS + 1)))
        assertNotNull(PythonExecutionPolicy.rejection("print('x')\u0000"))
    }
}
