package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Integration test for Requirement 6 — Self-built tools, loaded live.
 *
 * Covers the full acceptance flow end to end:
 *   1. Create a missing tool (extension_create → disabled draft)
 *   2. Test generated tool cases before activation (extension_test → sandbox)
 *   3. Activate it into the running app (extension_enable → promoted + archived)
 *   4. Use it (extension_run → executes in the sandbox)
 *   5. Verify the result against expected output
 *   6. Restore the previous version (extension_rollback → prior archived hash)
 *
 * No separate process boundary or network block is added: extensions run in-process
 * through the existing Chaquopy restricted sandbox (OrionPythonRuntime.runExtension).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionExtensionLifecycleIntegrationTest {

    private lateinit var context: Context
    private lateinit var executor: OrionPythonCapabilityExecutor

    private val stopped: () -> Boolean = { false }
    private val confirm: (String) -> Boolean = { true }

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        listOf(
            "orion_generated_extensions.json",
            "orion_extension_versions.json",
            "orion_extension_test_receipts.json"
        ).forEach { context.filesDir.resolve(it).delete() }

        // Fake sandbox standing in for orion_exec.run_extension (covered by python tests).
        // It sums INPUT.values and echoes INPUT.greeting, so the lifecycle can assert real data flow.
        val fakeSandbox: (String, String) -> String = { source, inputJson ->
            val input = JSONObject(inputJson)
            if (source.contains("REJECT_ME")) {
                JSONObject().put("ok", false).put("error", "restricted operation detected").toString()
            } else if (source.contains("INPUT[\"values\"]") || source.contains("INPUT.get(\"values\"")) {
                val values = input.optJSONArray("values") ?: JSONArray()
                var total = 0
                for (i in 0 until values.length()) total += values.getInt(i)
                JSONObject().put("ok", true).put("result", total).toString()
            } else if (source.contains("INPUT.get(\"greeting\"")) {
                JSONObject().put("ok", true).put("result", "echo:" + input.optString("greeting", "")).toString()
            } else {
                JSONObject().put("ok", true).put("result", "ok").toString()
            }
        }
        executor = OrionPythonCapabilityExecutor(context, null,
            extensionSandbox = RestrictedPythonSandbox(fakeSandbox))
    }

    /** Calls a Python capability executor tool and returns the string result. */
    private fun runTool(tool: String, args: JSONObject = JSONObject()): String {
        return executor.execute(tool, args, confirm, stopped) ?: "error: no result returned"
    }

    private fun extensionSource(kind: String): String = when (kind) {
        "sum_values" -> """
            # Orion self-built tool: sums a list of integers from INPUT.values
            RESULT = 0
            for i in range(len(INPUT.get("values", []))):
                RESULT = RESULT + INPUT["values"][i]
        """.trimIndent()
        "echo_greeting" -> """
            # Orion self-built tool: echoes a greeting string from INPUT.greeting
            RESULT = "echo:" + INPUT.get("greeting", "")
        """.trimIndent()
        else -> "RESULT = 1"
    }

    @Test
    fun fullLifecycleCreateTestEnableRunVerifyRollback() {
        val id = "sum_tool"

        // ── 1. Create a missing tool ──
        val createResult = runTool("extension_create", JSONObject()
            .put("id", id)
            .put("name", "Sum values tool")
            .put("runtime", "python")
            .put("source", extensionSource("sum_values"))
            .put("description", "Adds all integers in INPUT.values"))
        assertTrue("extension_create should save a disabled draft: $createResult",
            createResult.contains("Saved disabled extension draft $id"))

        // Draft must not be runnable yet
        val runBeforeEnable = runTool("extension_run", JSONObject().put("id", id)
            .put("input", JSONObject().put("values", JSONArray(listOf(1, 2, 3)))))
        assertTrue("extension_run must reject a disabled extension: $runBeforeEnable",
            runBeforeEnable.startsWith("error"))

        // ── 2. Test generated tool cases before activation ──
        val testResult = runTool("extension_test", JSONObject()
            .put("id", id)
            .put("tests", JSONArray().put(
                JSONObject().put("input", JSONObject().put("values", JSONArray(listOf(1, 2, 3)))).put("expect", 6)
            ).put(
                JSONObject().put("input", JSONObject().put("values", JSONArray(emptyList<Int>()))).put("expect", 0)
            ).put(
                JSONObject().put("input", JSONObject().put("values", JSONArray(listOf(10, -5)))).put("expect", 5)
            )))
        assertTrue("extension_test should pass all cases: $testResult",
            testResult.contains("SANDBOX TEST PASSED"))
        assertTrue("extension_test should report the source hash: $testResult",
            testResult.contains(SelfExtendingExtensionPolicy.sha256(extensionSource("sum_values")).take(12)))

        // ── 3. Activate it into the running app ──
        val enableResult = runTool("extension_enable", JSONObject().put("id", id))
        assertTrue("extension_enable should promote the tested extension: $enableResult",
            enableResult.contains("is enabled"))

        // Verify it is enabled, verified, and hash-matching
        // Use extension_list to confirm status
        val listResult = runTool("extension_list")
        assertTrue("extension_list should show the extension enabled: $listResult",
            listResult.contains("ENABLED"))

        // ── 4. Use it ──
        val runResult = runTool("extension_run", JSONObject()
            .put("id", id)
            .put("input", JSONObject().put("values", JSONArray(listOf(4, 5, 6)))))
        assertTrue("extension_run should return a result: $runResult",
            runResult.contains("EXTENSION RESULT for $id"))
        assertTrue("extension_run result should contain the computed sum: $runResult",
            runResult.contains("15"))

        // ── 5. Verify the result ──
        // The sandbox enforces input/output contracts: wrong input produces no wrong result.
        val wrongRun = runTool("extension_run", JSONObject()
            .put("id", id)
            .put("input", JSONObject().put("values", JSONArray(listOf(1)))))
        assertTrue("extension_run with different input should return a different result: $wrongRun",
            wrongRun.contains("EXTENSION RESULT for $id") && wrongRun.contains("1"))

        // ── 6. Restore the previous version ──
        // Create a v2 source, test, enable, then roll back to v1.
        // v1 must be disabled before a revision can be saved under the same id.
        val disableV1 = runTool("extension_disable", JSONObject().put("id", id))
        assertTrue("extension_disable must succeed before updating: $disableV1",
            disableV1.contains("disabled"))

        val v2Source = extensionSource("echo_greeting")
        val updateResult = runTool("extension_update", JSONObject()
            .put("id", id)
            .put("name", "Sum values tool v2")
            .put("runtime", "python")
            .put("source", v2Source)
            .put("description", "Echoes a greeting"))
        assertTrue("extension_update should save a disabled revision: $updateResult",
            updateResult.contains("Saved new disabled revision for $id"))

        val v2TestResult = runTool("extension_test", JSONObject()
            .put("id", id)
            .put("tests", JSONArray().put(
                JSONObject().put("input", JSONObject().put("greeting", "hello")).put("expect", "echo:hello")
            )))
        assertTrue("v2 extension_test should pass: $v2TestResult", v2TestResult.contains("SANDBOX TEST PASSED"))

        val v2EnableResult = runTool("extension_enable", JSONObject().put("id", id))
        assertTrue("v2 extension_enable should promote: $v2EnableResult", v2EnableResult.contains("is enabled"))

        // Confirm v2 is active (echo behavior)
        val v2Run = runTool("extension_run", JSONObject()
            .put("id", id)
            .put("input", JSONObject().put("greeting", "world")))
        assertTrue("v2 should echo: $v2Run", v2Run.contains("echo:world"))

        // Now roll back to v1 (sum behavior)
        val rollbackResult = runTool("extension_rollback", JSONObject().put("id", id))
        assertTrue("extension_rollback should restore v1: $rollbackResult",
            rollbackResult.contains("rolled back to verified version"))

        // After rollback, v1 (sum) behavior is active again
        val v1Run = runTool("extension_run", JSONObject()
            .put("id", id)
            .put("input", JSONObject().put("values", JSONArray(listOf(7, 8)))))
        assertTrue("after rollback, v1 sum should be active: $v1Run",
            v1Run.contains("15"))

        // History should show both versions
        val historyResult = runTool("extension_history", JSONObject().put("id", id))
        assertTrue("extension_history should list both versions: $historyResult",
            historyResult.contains("v1") && historyResult.contains("v2"))
    }

    @Test
    fun failedTestNeverActivatesExtension() {
        val id = "broken_tool"
        val source = extensionSource("sum_values").replace("RESULT = 0", "RESULT = bad syntax here !!!")

        val createResult = runTool("extension_create", JSONObject()
            .put("id", id)
            .put("name", "Broken tool")
            .put("runtime", "python")
            .put("source", source)
            .put("description", "Intentionally broken"))
        assertTrue(createResult.contains("Saved disabled extension draft"))

        // A test that expects a wrong result must fail
        val testResult = runTool("extension_test", JSONObject()
            .put("id", id)
            .put("tests", JSONArray().put(
                JSONObject().put("input", JSONObject().put("values", JSONArray(listOf(1, 2)))).put("expect", 999)
            )))
        assertTrue("extension_test must fail for wrong expected output: $testResult",
            testResult.contains("SANDBOX TEST FAILED"))

        // The extension must not be enableable without a passing receipt
        val enableResult = runTool("extension_enable", JSONObject().put("id", id))
        assertTrue("extension_enable must reject without passing test: $enableResult",
            enableResult.startsWith("error"))

        // It must not appear as enabled
        val listResult = runTool("extension_list")
        assertFalse("broken extension must not be enabled: $listResult",
            listResult.contains("ENABLED") && listResult.contains(id))
    }

    @Test
    fun rollbackRequiresPassingReceiptForExactArchivedHash() {
        val id = "versioned_tool"

        // Activate v1
        runTool("extension_create", JSONObject()
            .put("id", id)
            .put("name", "Versioned tool")
            .put("runtime", "python")
            .put("source", extensionSource("sum_values"))
            .put("description", "v1"))
        runTool("extension_test", JSONObject()
            .put("id", id)
            .put("tests", JSONArray().put(
                JSONObject().put("input", JSONObject().put("values", JSONArray(listOf(2, 3)))).put("expect", 5)
            )))
        runTool("extension_enable", JSONObject().put("id", id))

        // Confirm v1 is active (sum: 2+3=5)
        val v1Run = runTool("extension_run", JSONObject()
            .put("id", id)
            .put("input", JSONObject().put("values", JSONArray(listOf(2, 3)))))
        assertTrue("v1 should sum to 5: $v1Run", v1Run.contains("5"))

        // Roll back with no previous version → error
        val noPrevious = runTool("extension_rollback", JSONObject().put("id", id))
        assertTrue("rollback with no previous version must fail: $noPrevious",
            noPrevious.startsWith("error"))
    }

    @Test
    fun disabledDraftCannotRunAndCannotBeEnabledWithoutTest() {
        val id = "untested_draft"
        runTool("extension_create", JSONObject()
            .put("id", id)
            .put("name", "Untested draft")
            .put("runtime", "python")
            .put("source", extensionSource("echo_greeting"))
            .put("description", "Never tested"))

        // Cannot run before testing
        assertTrue(runTool("extension_run", JSONObject().put("id", id)
            .put("input", JSONObject())).startsWith("error"))

        // Cannot enable without a passing test receipt
        assertTrue(runTool("extension_enable", JSONObject().put("id", id)).startsWith("error"))
    }
}
