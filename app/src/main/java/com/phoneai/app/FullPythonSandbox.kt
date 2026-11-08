package com.phoneai.app

import org.json.JSONObject

/**
 * Runs `python_full` extensions in Orion's full CPython workspace runtime (the same one python_exec uses),
 * so a self-built tool can read and write files, use the network, import installed packages and call the
 * phone bridge. The source sees INPUT (parsed JSON) and sets RESULT (any JSON value); the same test-before-
 * activation receipts apply as for restricted extensions. Delegates `python` runtime to [restricted].
 */
internal class FullPythonSandbox(
    private val restricted: ExtensionSandbox,
    private val runFull: (code: String) -> String
) : ExtensionSandbox {
    private val marker = "__ORION_EXT_RESULT__"

    override fun test(extension: GeneratedExtension, testCases: List<JSONObject>): ExtensionTestReport {
        if (extension.runtime != "python_full") return restricted.test(extension, testCases)
        val hash = SelfExtendingExtensionPolicy.sha256(extension.source)
        fun fail(s: String, o: String = "") = ExtensionTestReport(false, hash, s, o.take(SelfExtendingExtensionPolicy.MAX_OUTPUT_CHARS))
        if (testCases.isEmpty()) return fail("At least one test case with input and expect is required.")
        if (testCases.size > SelfExtendingExtensionPolicy.MAX_TESTS) return fail("At most ${SelfExtendingExtensionPolicy.MAX_TESTS} test cases are allowed.")
        if (testCases.any { !it.has("expect") }) return fail("Every test case needs an \"expect\" value.")
        for ((i, case) in testCases.withIndex()) {
            val reply = invoke(extension.source, case.opt("input"))
            if (!reply.optBoolean("ok", false)) return fail("Test ${i + 1} failed: ${reply.optString("error")}".take(300))
            if (!RestrictedPythonSandbox.sameJson(reply.opt("result"), case.opt("expect")))
                return fail("Test ${i + 1} failed: expected ${case.opt("expect")} but got ${reply.opt("result")}".take(300))
        }
        return ExtensionTestReport(true, hash, "${testCases.size}/${testCases.size} test cases passed in the full Python runtime.")
    }

    override fun execute(extension: GeneratedExtension, input: JSONObject, grantedCapabilities: Set<String>): JSONObject =
        if (extension.runtime != "python_full") restricted.execute(extension, input, grantedCapabilities)
        else invoke(extension.source, input)

    private fun invoke(source: String, input: Any?): JSONObject {
        val inputText = when (input) { null, JSONObject.NULL -> "null"; is JSONObject, is org.json.JSONArray, is Number, is Boolean -> input.toString(); else -> JSONObject.quote(input.toString()) }
        val hex = inputText.toByteArray().joinToString("") { "%02x".format(it) }
        val code = "import json\nINPUT = json.loads(bytes.fromhex('$hex').decode())\nRESULT = None\n" +
            source + "\nprint('$marker' + json.dumps(RESULT))\n"
        val out = try { runFull(code) } catch (e: Exception) { return JSONObject().put("ok", false).put("error", (e.message ?: "run failed").take(200)) }
        val line = out.lineSequence().lastOrNull { it.contains(marker) }
            ?: return JSONObject().put("ok", false).put("error", out.take(300).ifBlank { "no result produced" })
        return try {
            JSONObject().put("ok", true).put("result", org.json.JSONTokener(line.substringAfter(marker)).nextValue())
                .put("printed", out.substringBefore(marker).take(1000))
        } catch (_: Exception) { JSONObject().put("ok", false).put("error", "RESULT was not JSON serialisable") }
    }
}
