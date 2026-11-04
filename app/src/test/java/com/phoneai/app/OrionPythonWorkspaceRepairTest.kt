package com.phoneai.app

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
class OrionPythonWorkspaceRepairTest {
    private lateinit var context: Context
    private lateinit var sessionId: String
    private lateinit var root: File

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        OrionConversationRepository.resetForTests()
        context.getSharedPreferences("orion_chat_history", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("orion_conversations.db")
        File(context.filesDir, "orion_evidence_ledger.json").delete()
        root = File(context.filesDir, "orion-python/workspace")
        root.deleteRecursively()
        OrionConversationRepository.get(context).recordEvent(context, null, "repair_test", "started", "workspace repair test")
        sessionId = requireNotNull(OrionConversationRepository.get(context).activeSessionId(context))
    }

    @After fun tearDown() {
        OrionConversationRepository.resetForTests()
        root.deleteRecursively()
        context.getSharedPreferences("orion_chat_history", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("orion_conversations.db")
        File(context.filesDir, "orion_evidence_ledger.json").delete()
    }

    @Test fun guestVisibleWorkspacePathsResolveToTheSamePrivateFiles() {
        val workspace = OrionPythonWorkspace(context)
        assertTrue(workspace.write("orion_acceptance_test/diagnostic.py", "print('ORION_OK')").startsWith("saved "))
        assertEquals("print('ORION_OK')", workspace.read("/workspace/orion_acceptance_test/diagnostic.py"))
        assertTrue(workspace.write("/workspace/orion_acceptance_test/report.json", "{\"ok\":true}").startsWith("saved "))
        assertEquals("{\"ok\":true}", workspace.read("orion_acceptance_test/report.json"))
        assertTrue(workspace.read("/workspace/missing.txt").startsWith("error:"))
        assertTrue(workspace.read("/data/local/tmp/escape.txt").startsWith("error:"))
    }


    @Test fun shellAndPythonShareTheSameMultiFileProjectWorkspace() {
        val pythonWorkspace = OrionPythonWorkspace(context)
        val first = "projects/demo/src/main.py"
        val second = "projects/demo/src/helpers.py"
        assertTrue(ShellRunner.writeFile(context, first, "from helpers import answer\nprint(answer())").startsWith("wrote "))
        assertTrue(pythonWorkspace.write(second, "def answer():\n    return 42\n").startsWith("saved "))
        assertEquals("from helpers import answer\nprint(answer())", pythonWorkspace.read(first))
        assertEquals("def answer():\n    return 42\n", ShellRunner.readFile(context, second))
        assertEquals(root.canonicalFile, ShellRunner.workspace(context).canonicalFile)
        assertTrue(pythonWorkspace.list().contains(first))
        assertTrue(pythonWorkspace.list().contains(second))
    }

    @Test fun sharedProjectWorkspaceAllowsDependencyTreesButKeepsListingsFocused() {
        val workspace = OrionPythonWorkspace(context)
        assertTrue(workspace.write("projects/webapp/project.json", "{\\"language\\":\\"javascript\\"}").startsWith("saved "))
        assertTrue(workspace.write("projects/webapp/src/index.js", "export const ok = true;").startsWith("saved "))
        assertTrue(workspace.write("projects/webapp/node_modules/example/index.js", "module.exports = true;").startsWith("saved "))
        val listing = workspace.list()
        assertTrue(listing.contains("projects/webapp/src/index.js"))
        assertFalse(listing.contains("node_modules"))
        assertEquals(256L * 1024 * 1024, OrionPythonWorkspace.MAX_TOTAL_BYTES)
    }

    @Test fun diagnosticsReportMissingWorkspaceRootWithEvidence() {
        val report = OrionSelfDiagnostics.inspect(context)
        assertTrue(report.findings.any {
            it.component == "python_workspace" &&
                it.title == "Python workspace root is missing" &&
                it.evidenceRefs.contains("OrionPythonWorkspace:root_missing")
        })
    }

    @Test fun centralBrainAppliesOnlyTheRegisteredReversibleWorkspaceRepairWithoutExtraPrompt() {
        assertEquals(OrionPythonWorkspace.RootStatus.MISSING, OrionPythonWorkspace(context).rootStatus())
        var prompts = 0
        val result = OrionDiagnosticsCapabilityExecutor.execute(
            "repair_apply",
            JSONObject().put("repair_id", "ensure_python_workspace_root"),
            context
        ) { prompts++; true }

        assertTrue(result.orEmpty().contains("Repair verified:"))
        assertEquals(0, prompts)
        assertEquals(OrionPythonWorkspace.RootStatus.READY, OrionPythonWorkspace(context).rootStatus())
        val taskId = "repair-python-workspace-${sessionId.take(32)}"
        assertTrue(OrionEvidenceLedger.events(context, taskId).any {
            it.backend == "repair_lifecycle" && it.tool == "repair_verified" &&
                it.outcome == OrionEvidenceLedger.Outcome.VERIFIED
        })
    }

    @Test fun unknownRepairIdsFailClosedAndCannotCreateFiles() {
        val result = OrionDiagnosticsCapabilityExecutor.execute(
            "repair_apply", JSONObject().put("repair_id", "run_arbitrary_shell"), context
        )
        assertTrue(result.orEmpty().startsWith("error:"))
        assertFalse(root.exists())
    }

    @Test fun healthyWorkspaceDoesNotRunASecondRepair() {
        assertTrue(OrionPythonWorkspace(context).ensureRootForRepair() != null)
        val result = OrionDiagnosticsCapabilityExecutor.execute(
            "repair_apply", JSONObject().put("repair_id", "ensure_python_workspace_root"), context
        )
        assertTrue(result.orEmpty().contains("No repair needed"))
    }
}
