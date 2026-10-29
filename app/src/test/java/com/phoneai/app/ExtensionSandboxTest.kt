package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExtensionSandboxTest {
    private fun ext(source: String = "RESULT = 1", runtime: String = "python", enabled: Boolean = false, verified: Boolean = false) =
        GeneratedExtension("sum_values", "Sum values", runtime, source, SelfExtendingExtensionPolicy.sha256(source), "Adds numbers", 1L, enabled, verified)

    private fun case(input: String, expect: Any?) = JSONObject().put("input", JSONObject(input)).put("expect", expect ?: JSONObject.NULL)

    /** Stand-in for orion_exec.run_extension: sums INPUT.values; the real interpreter is covered by tests/python. */
    private val fake: (String, String) -> String = { source, inputJson ->
        if (source.contains("boom")) JSONObject().put("ok", false).put("error", "policy rejected the extension: unsupported Python syntax: Import").toString()
        else {
            val values = JSONObject(inputJson).optJSONArray("values") ?: JSONArray()
            JSONObject().put("ok", true).put("result", (0 until values.length()).sumOf { values.getInt(it) }).put("printed", "").toString()
        }
    }

    @Test fun passingCasesProduceAReportBoundToTheSourceHash() {
        val e = ext()
        val report = RestrictedPythonSandbox(fake).test(e, listOf(case("{\"values\":[1,2,3]}", 6), case("{\"values\":[]}", 0)))
        assertTrue(report.summary, report.passed)
        assertEquals(SelfExtendingExtensionPolicy.sha256(e.source), report.testedSourceSha256)
        assertTrue(SelfExtendingExtensionPolicy.mayPromote(report.passed, e.sourceSha256, report.testedSourceSha256, userApproved = true))
        assertFalse("the user's approval is still required", SelfExtendingExtensionPolicy.mayPromote(report.passed, e.sourceSha256, report.testedSourceSha256, userApproved = false))
    }

    @Test fun aWrongResultFailsAndNamesTheCase() {
        val report = RestrictedPythonSandbox(fake).test(ext(), listOf(case("{\"values\":[1,2]}", 99)))
        assertFalse(report.passed)
        assertTrue(report.summary, report.summary.contains("Test 1") && report.summary.contains("expected 99") && report.summary.contains("got 3"))
    }

    @Test fun sandboxRejectionIsReportedNotHidden() {
        val report = RestrictedPythonSandbox(fake).test(ext("boom"), listOf(case("{}", 0)))
        assertFalse(report.passed)
        assertTrue(report.summary.contains("unsupported Python syntax"))
    }

    @Test fun testsWithoutExpectedValuesOrWithoutCasesNeverPass() {
        val sb = RestrictedPythonSandbox(fake)
        assertFalse(sb.test(ext(), emptyList()).passed)
        assertFalse(sb.test(ext(), listOf(JSONObject().put("input", JSONObject()))).passed)
        assertFalse(sb.test(ext(), List(SelfExtendingExtensionPolicy.MAX_TESTS + 1) { case("{\"values\":[1]}", 1) }).passed)
    }

    @Test fun javascriptHasNoSandboxInThisBuild() {
        val report = RestrictedPythonSandbox(fake).test(ext("RESULT = 1", runtime = "javascript"), listOf(case("{}", 1)))
        assertFalse(report.passed)
        assertFalse(RestrictedPythonSandbox(fake).execute(ext(runtime = "javascript"), JSONObject(), emptySet()).optBoolean("ok"))
    }

    @Test fun executeRunsTheExtensionAndGrantsNoCapabilities() {
        var grantedAnything = false
        val sb = RestrictedPythonSandbox { s, i -> fake(s, i) }
        val out = sb.execute(ext(), JSONObject("{\"values\":[4,5]}"), setOf("network", "files"))
        assertTrue(out.optBoolean("ok")); assertEquals(9, out.getInt("result"))
        assertFalse(grantedAnything)
    }

    @Test fun malformedSandboxOutputIsAFailureNotACrash() {
        val out = RestrictedPythonSandbox { _, _ -> "not json" }.execute(ext(), JSONObject(), emptySet())
        assertFalse(out.optBoolean("ok"))
    }

    @Test fun jsonComparisonIsStructuralAndNumericAware() {
        assertTrue(RestrictedPythonSandbox.sameJson(2, 2.0))
        assertTrue(RestrictedPythonSandbox.sameJson(JSONObject("{\"a\":[1,2]}"), JSONObject("{\"a\":[1,2.0]}")))
        assertFalse(RestrictedPythonSandbox.sameJson(JSONObject("{\"a\":1}"), JSONObject("{\"a\":1,\"b\":2}")))
        assertFalse(RestrictedPythonSandbox.sameJson("2", 2))
        assertTrue(RestrictedPythonSandbox.sameJson(JSONObject.NULL, null))
    }

    @Test fun plannerOnlySeesVerifiedIntactEnabledExtensions() {
        val good = ext(enabled = true, verified = true)
        val draft = ext("RESULT = 2").copy(id = "draft_tool")
        val tampered = good.copy(id = "tampered_tool", source = "RESULT = 3") // hash no longer matches
        val block = ExtensionPrompt.block(listOf(good, draft, tampered))
        assertTrue(block.contains("sum_values")); assertFalse(block.contains("draft_tool")); assertFalse(block.contains("tampered_tool"))
        assertEquals("", ExtensionPrompt.block(listOf(draft)))
    }

    @Test fun statusLinesNeverOverstateAnExtension() {
        assertTrue(ExtensionStatus.line(ext()).contains("DRAFT"))
        assertTrue(ExtensionStatus.line(ext(enabled = true, verified = true)).contains("ENABLED"))
        assertTrue(ExtensionStatus.line(ext(enabled = true, verified = true).copy(source = "RESULT = 9")).contains("BLOCKED"))
        assertEquals("No generated tools yet.", ExtensionStatus.summary(emptyList()))
        assertTrue(ExtensionStatus.summary(listOf(ext(), ext(enabled = true, verified = true).copy(id = "b_tool"))).contains("1 enabled"))
    }
}
