package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionCodingPlannerTest {
    private lateinit var context: Context
    private lateinit var workspace: OrionPythonWorkspace

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "orion_coding_plans.json").delete()
        workspace = OrionPythonWorkspace(context)
    }

    @After fun tearDown() {
        File(context.filesDir, "orion_coding_plans.json").delete()
        File(context.filesDir, "orion-python").deleteRecursively()
    }

    private fun createProject() {
        assertTrue(OrionProjectWorkspace(context).create("demo", "python").startsWith("created "))
    }

    private fun plan(vararg rows: JSONObject): String {
        val files = JSONArray().apply { rows.forEach(::put) }
        return OrionCodingPlanner.create(context, "demo", "python", "Build a small tested project", "main.py", files)
    }

    private fun file(path: String, vararg dependencies: String) = JSONObject()
        .put("path", path)
        .put("purpose", "Implementation or test file")
        .put("depends_on", JSONArray().apply { dependencies.forEach(::put) })

    @Test fun projectWritesRespectDeclaredDependencyOrder() {
        createProject()
        assertTrue(plan(file("src/helpers.py"), file("main.py", "src/helpers.py")).startsWith("CODING PLAN SAVED"))
        assertTrue(OrionCodingPlanner.writeGate(context, "projects/demo/main.py")!!.contains("write and verify these dependencies first"))
        assertTrue(workspace.write("projects/demo/src/helpers.py", "def answer(): return 42").startsWith("saved "))
        assertTrue(OrionCodingPlanner.noteFileWritten(context, "projects/demo/src/helpers.py"))
        assertTrue(OrionCodingPlanner.writeGate(context, "projects/demo/main.py") == null)
    }

    @Test fun projectCannotClaimTestVerificationWithoutPlannedTestFile() {
        createProject()
        assertTrue(plan(file("main.py")).startsWith("CODING PLAN SAVED"))
        assertTrue(workspace.write("projects/demo/main.py", "print(42)").startsWith("saved "))
        assertTrue(OrionCodingPlanner.noteFileWritten(context, "projects/demo/main.py"))
        var approvalRequested = false
        val result = OrionCodingPlanner.verify(context, "demo", runTests = true) {
            approvalRequested = true
            true
        }
        assertTrue(result.startsWith("TESTS_NOT_PLANNED:"))
        assertFalse(approvalRequested)
    }

    @Test fun unreadablePlanStoreFailsClosedForPlannedWrites() {
        File(context.filesDir, "orion_coding_plans.json").writeText("{ broken")
        val result = OrionCodingPlanner.writeGate(context, "projects/demo/main.py")
        assertTrue(result!!.startsWith("error: coding plan store is unreadable"))
    }
}
