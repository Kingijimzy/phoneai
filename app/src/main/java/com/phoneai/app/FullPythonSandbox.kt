package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime router for generated extensions.
 *
 * "python" retains the compact data-transform interpreter.
 * "python_full" executes in Orion's full CPython workspace runtime, with standard-library,
 * filesystem and network support available to the guest process. PRoot runs under the Android
 * app UID; Android's own app sandbox and granted platform permissions still apply.
 */
internal class FullPythonSandbox(
    private val restricted: ExtensionSandbox,
    private val runFull: (source: String, inputJson: String) -> String
) : ExtensionSandbox {

    override fun test(extension: GeneratedExtension, testCases: List<JSONObject>): ExtensionTestReport {
        if (extension.runtime != "python_full") return restricted.test(extension, testCases)
        val hash = SelfExtendingExtensionPolicy.sha256(extension.source)
        fun fail(summary: String, output: String = "") =
            ExtensionTestReport(false, hash, summary, output.take(SelfExtendingExtensionPolicy.MAX_OUTPUT_CHARS))
        if (testCases.isEmpty()) return fail("At least one test case with an input and expected result is required.")
        if (testCases.size > SelfExtendingExtensionPolicy.MAX_TESTS) {
            return fail("At most " + SelfExtendingExtensionPolicy.MAX_TESTS + " test cases are allowed.")
        }
        if (testCases.any { !it.has("expect") }) return fail("Every test case needs an expected result.")
        var passed = 0
        val output = StringBuilder()
        for ((index, case) in testCases.withIndex()) {
            val reply = parse(runFull(extension.source, jsonText(case.opt("input"))))
                ?: return fail("Full CPython test " + (index + 1) + " did not return a valid result.", output.toString())
            if (!reply.optBoolean("ok", false)) {
                return fail("Full CPython test " + (index + 1) + " failed: " + reply.optString("error"), output.toString())
            }
            val actual = reply.opt("result")
            if (!sameJson(actual, case.opt("expect"))) {
                return fail("Full CPython test " + (index + 1) + " expected " + render(case.opt("expect")) + " but got " + render(actual), output.toString())
            }
            output.append(reply.optString("printed"))
            passed++
        }
        return ExtensionTestReport(true, hash, "$passed/" + testCases.size + " test cases passed in full CPython.", output.toString())
    }

    override fun execute(extension: GeneratedExtension, input: JSONObject, grantedCapabilities: Set<String>): JSONObject {
        if (extension.runtime != "python_full") return restricted.execute(extension, input, grantedCapabilities)
        return parse(runFull(extension.source, input.toString()))
            ?: JSONObject().put("ok", false).put("error", "Full CPython returned no structured extension result.")
    }

    private fun parse(value: String): JSONObject? = try { JSONObject(value) } catch (_: Exception) { null }

    private fun jsonText(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject, is JSONArray, is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun render(value: Any?): String = jsonText(value).take(160)

    private fun sameJson(a: Any?, b: Any?): Boolean {
        val x = if (a == JSONObject.NULL) null else a
        val y = if (b == JSONObject.NULL) null else b
        return when {
            x == null || y == null -> x == null && y == null
            x is Number && y is Number -> x.toDouble() == y.toDouble()
            x is JSONObject && y is JSONObject ->
                x.length() == y.length() && x.keys().asSequence().all { y.has(it) && sameJson(x.opt(it), y.opt(it)) }
            x is JSONArray && y is JSONArray ->
                x.length() == y.length() && (0 until x.length()).all { sameJson(x.opt(it), y.opt(it)) }
            else -> x == y
        }
    }
}
