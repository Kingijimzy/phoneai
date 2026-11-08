package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FullPythonSandboxTest {
    private val restricted = RestrictedPythonSandbox { _, _ -> """{"ok":true,"result":1}""" }
    private fun ext(src: String, runtime: String = "python_full") =
        GeneratedExtension("fx_tool", "Fx", runtime, src, SelfExtendingExtensionPolicy.sha256(src), "d", 0L)

    @Test fun fullRuntimeAllowsFilesNetworkAndPackages() {
        assertNull(SelfExtendingExtensionPolicy.validateSource("python_full", "import os, requests\nRESULT = open('/tmp/x').read()"))
        assertNotNull("restricted python still blocks them", SelfExtendingExtensionPolicy.validateSource("python", "import os\nos.system('id')"))
    }

    @Test fun passingAndFailingTestsAreReportedFromTheRuntimeOutput() {
        val sb = FullPythonSandbox(restricted) { code ->
            assertTrue(code.contains("INPUT = json.loads"))
            "noise\n__ORION_EXT_RESULT__42\n"
        }
        val pass = sb.test(ext("RESULT = 42"), listOf(JSONObject().put("input", JSONObject()).put("expect", 42)))
        assertTrue(pass.summary, pass.passed)
        val fail = sb.test(ext("RESULT = 42"), listOf(JSONObject().put("input", JSONObject()).put("expect", 7)))
        assertFalse(fail.passed)
        assertEquals(42, sb.execute(ext("RESULT = 42"), JSONObject(), emptySet()).opt("result"))
    }

    @Test fun restrictedRuntimeIsStillDelegated() {
        val sb = FullPythonSandbox(restricted) { error("must not run") }
        assertEquals(1, sb.execute(ext("RESULT = 1", "python"), JSONObject(), emptySet()).opt("result"))
    }
}
