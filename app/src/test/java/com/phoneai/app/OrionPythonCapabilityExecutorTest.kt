package com.phoneai.app

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionPythonCapabilityExecutorTest {
    private lateinit var context: Context
    private lateinit var executor: OrionPythonCapabilityExecutor
    private var executedSource = ""

    private val sandbox = object : ExtensionSandbox {
        override fun test(extension: GeneratedExtension, testCases: List<JSONObject>): ExtensionTestReport {
            val passed = testCases.isNotEmpty() && testCases.all { it.has("expect") }
            return ExtensionTestReport(passed, extension.sourceSha256, if (passed) "all fake cases passed" else "missing expected value")
        }

        override fun execute(extension: GeneratedExtension, input: JSONObject, grantedCapabilities: Set<String>): JSONObject {
            executedSource = extension.source
            return JSONObject().put("ok", true).put("result", extension.source)
        }
    }

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        listOf(
            "orion_generated_extensions.json",
            "orion_extension_test_receipts.json",
            "orion_extension_versions.json"
        ).forEach { File(context.filesDir, it).delete() }
        executedSource = ""
        executor = OrionPythonCapabilityExecutor(context, null, extensionSandbox = sandbox)
    }

    private fun source(version: Int) = "def run(value):\n    return $version"
    private fun create(source: String) = executor.execute(
        "extension_create",
        JSONObject().put("id", "live_helper").put("name", "Live helper")
            .put("runtime", "python").put("source", source).put("description", "test extension"),
        { true }, { false }
    ).orEmpty()

    private fun test(source: String) = executor.execute(
        "extension_test",
        JSONObject().put("id", "live_helper").put("tests",
            org.json.JSONArray().put(JSONObject().put("input", JSONObject().put("x", 1)).put("expect", 1))),
        { true }, { false }
    ).orEmpty()

    private fun enable() = executor.execute(
        "extension_enable", JSONObject().put("id", "live_helper"), { true }, { false }
    ).orEmpty()

    @Test fun developerPythonUsesTheFullRegisteredPythonExecutor() {
        assertTrue(OrionPythonCapabilityExecutor.supports("python_exec"))
        assertTrue(OrionPythonCapabilityExecutor.supports("dev_python"))
    }

    @Test fun exactTestedSourceIsLoadedForEveryLiveInvocation() {
        val v1 = source(1)
        assertTrue(create(v1).contains("disabled extension draft"))
        assertTrue(test(v1).contains("SANDBOX TEST PASSED and AUTO-ACTIVATED"))
        val result = executor.execute(
            "extension_run", JSONObject().put("id", "live_helper").put("input", JSONObject().put("x", 2)),
            { true }, { false }
        ).orEmpty()
        assertTrue(result.contains("EXTENSION RESULT"))
        assertEquals(v1, executedSource)
    }

    @Test fun rollbackReactivatesOnlyPreviouslyTestedExactHashAndLoadsItLive() {
        val v1 = source(1)
        assertTrue(create(v1).contains("draft"))
        assertTrue(test(v1).contains("AUTO-ACTIVATED"))

        assertTrue(executor.execute(
            "extension_disable", JSONObject().put("id", "live_helper"), { true }, { false }
        ).orEmpty().contains("disabled"))

        val v2 = source(2)
        assertTrue(executor.execute(
            "extension_update",
            JSONObject().put("id", "live_helper").put("name", "Live helper")
                .put("runtime", "python").put("source", v2).put("description", "revision"),
            { true }, { false }
        ).orEmpty().contains("revision"))
        assertTrue(test(v2).contains("AUTO-ACTIVATED"))

        val rolledBack = executor.execute(
            "extension_rollback", JSONObject().put("id", "live_helper"), { true }, { false }
        ).orEmpty()
        assertTrue(rolledBack, rolledBack.contains("rolled back"))
        executor.execute(
            "extension_run", JSONObject().put("id", "live_helper"), { true }, { false }
        )
        assertEquals("Rollback must dynamically activate the previous tested source", v1, executedSource)
    }

    @Test fun changedSourceCannotRunUntilRetestedThenAutoActivatesVerifiedHash() {
        val v1 = source(1)
        assertTrue(create(v1).contains("draft"))
        assertTrue(test(v1).contains("AUTO-ACTIVATED"))
        assertTrue(executor.execute(
            "extension_disable", JSONObject().put("id", "live_helper"), { true }, { false }
        ).orEmpty().contains("disabled"))
        val v2 = source(2)
        assertTrue(executor.execute(
            "extension_update",
            JSONObject().put("id", "live_helper").put("name", "Live helper")
                .put("runtime", "python").put("source", v2).put("description", "revision"),
            { true }, { false }
        ).orEmpty().contains("revision"))
        val run = executor.execute("extension_run", JSONObject().put("id", "live_helper"), { true }, { false }).orEmpty()
        assertTrue(run.startsWith("error:"))
        assertEquals("", executedSource)
        assertTrue(test(v2).contains("AUTO-ACTIVATED"))
        val afterVerification = executor.execute("extension_run", JSONObject().put("id", "live_helper"), { true }, { false }).orEmpty()
        assertTrue(afterVerification.contains("EXTENSION RESULT"))
        assertEquals("Only the exact source that passed the sandbox may auto-activate", v2, executedSource)
    }
}
