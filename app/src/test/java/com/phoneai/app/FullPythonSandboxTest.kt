package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FullPythonSandboxTest {
    private val restricted = RestrictedPythonSandbox { _, _ -> """{"ok":true,"result":1}""" }
    private fun ext(runtime: String = "python_full") =
        GeneratedExtension("fx_tool", "Fx", runtime, "RESULT = 1", SelfExtendingExtensionPolicy.sha256("RESULT = 1"), "d", 0L)

    @Test fun fullRuntimeIsRejectedBecauseProotIsNotAnOsSecurityBoundary() {
        assertNotNull(SelfExtendingExtensionPolicy.validateSource("python_full", "import os, requests\\nRESULT = open('/tmp/x').read()"))
        val sandbox = FullPythonSandbox(restricted)
        val report = sandbox.test(ext(), listOf(JSONObject().put("input", JSONObject()).put("expect", 1)))
        assertFalse(report.passed)
        assertTrue(report.summary.contains("not an OS security boundary"))
        val result = sandbox.execute(ext(), JSONObject(), setOf("files", "network"))
        assertFalse(result.optBoolean("ok"))
        assertTrue(result.optString("error").contains("not an OS-enforced sandbox"))
    }

    @Test fun restrictedRuntimeStillDelegatesToThePureInterpreter() {
        val sandbox = FullPythonSandbox(restricted)
        val report = sandbox.test(ext("python"), listOf(JSONObject().put("input", JSONObject()).put("expect", 1)))
        assertTrue(report.passed)
        assertEquals(1, sandbox.execute(ext("python"), JSONObject(), emptySet()).optInt("result"))
    }
}
