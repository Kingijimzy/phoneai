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

    @Test fun onlyUnrestrictedFullPythonRequiresPerRunApproval() {
        assertFalse(OrionPythonCapabilityExecutor.requiresExplicitApproval("python_exec", OrionPythonProfile.GUARDED))
        assertFalse(OrionPythonCapabilityExecutor.requiresExplicitApproval("python_exec", OrionPythonProfile.DEVELOPER))
        assertTrue(OrionPythonCapabilityExecutor.requiresExplicitApproval("python_exec", OrionPythonProfile.FULL))
        assertFalse(OrionPythonCapabilityExecutor.requiresExplicitApproval("extension_run", OrionPythonProfile.FULL))
    }

    @Test fun fullProfileAllowsLargerScripts() {
        assertNull(PythonExecutionPolicy.rejection("x".repeat(60_000), PythonExecutionPolicy.FULL_MAX_CODE_CHARS))
        assertNotNull(PythonExecutionPolicy.rejection("x".repeat(65_000), PythonExecutionPolicy.FULL_MAX_CODE_CHARS))
    }

    @Test fun rejectsEmptyOversizedAndNulInput() {
        assertNotNull(PythonExecutionPolicy.rejection("  "))
        assertNotNull(PythonExecutionPolicy.rejection("x".repeat(PythonExecutionPolicy.MAX_CODE_CHARS + 1)))
        assertNotNull(PythonExecutionPolicy.rejection("print('x')\u0000"))
    }
}
