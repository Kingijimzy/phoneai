package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FullPythonSandboxTest {
    private val restricted = RestrictedPythonSandbox { _, _ -> """{"ok":true,"result":1}""" }
    private val fullCalls = mutableListOf<Pair<String, String>>()
    private val fullRunner: (String, String) -> String = { source, input ->
        fullCalls += source to input
        val value = JSONObject(input).optInt("value", 0) + 1
        JSONObject().put("ok", true).put("result", value).put("printed", "").toString()
    }
    private fun ext(runtime: String = "python_full", source: String = "import os\nRESULT = 1") =
        GeneratedExtension("fx_tool", "Fx", runtime, source, SelfExtendingExtensionPolicy.sha256(source), "d", 0L)

    @Test fun fullRuntimeAcceptsNormalCPythonSourceAndRoutesTestAndExecutionToFullRunner() {
        val source = "import os, pathlib\nRESULT = len(os.listdir('.'))"
        assertNull(SelfExtendingExtensionPolicy.validateSource("python_full", source))
        val sandbox = FullPythonSandbox(restricted, fullRunner)
        val report = sandbox.test(ext(source = source), listOf(
            JSONObject().put("input", JSONObject().put("value", 40)).put("expect", 41)
        ))
        assertTrue(report.summary, report.passed)
        assertTrue(report.summary.contains("full CPython"))
        assertEquals(source, fullCalls.last().first)

        val result = sandbox.execute(ext(source = source), JSONObject().put("value", 8), emptySet())
        assertTrue(result.optBoolean("ok"))
        assertEquals(9, result.optInt("result"))
        assertEquals(source, fullCalls.last().first)
    }

    @Test fun fullRuntimeStillRejectsUnsupportedRuntimeNames() {
        assertNotNull(SelfExtendingExtensionPolicy.validateSource("ruby", "puts 'hi'"))
    }

    @Test fun restrictedRuntimeStillDelegatesToThePureInterpreter() {
        val sandbox = FullPythonSandbox(restricted, fullRunner)
        val report = sandbox.test(
            ext("python", "RESULT = 1"),
            listOf(JSONObject().put("input", JSONObject()).put("expect", 1))
        )
        assertTrue(report.passed)
        assertEquals(1, sandbox.execute(ext("python", "RESULT = 1"), JSONObject(), emptySet()).optInt("result"))
        assertTrue("restricted extensions must not invoke full CPython", fullCalls.isEmpty())
    }
}
