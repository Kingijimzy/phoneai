package com.phoneai.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class OrionPythonCapabilitiesTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "orion-python").deleteRecursively()
        context.getSharedPreferences("orion_python_network_hosts_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("orion_sensor_consent_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun workspacePersistsFilesOnlyInsideItsPrivateRoot() {
        val workspace = OrionPythonWorkspace(context)
        assertTrue(workspace.write("reports/week1.txt", "EURUSD bias bearish").startsWith("saved"))
        assertEquals("EURUSD bias bearish", workspace.read("reports/week1.txt"))
        assertTrue(workspace.list().contains("reports/week1.txt"))
        assertTrue(workspace.read("../outside.txt").startsWith("error:"))
        assertTrue(workspace.write("../../outside.txt", "escape").startsWith("error:"))
        assertFalse(File(context.filesDir, "outside.txt").exists())
    }

    @Test fun workspaceEnforcesPerFileAndTotalBounds() {
        val workspace = OrionPythonWorkspace(context)
        assertTrue(workspace.write("large.txt", "x".repeat(OrionPythonWorkspace.MAX_FILE_BYTES + 1)).startsWith("error:"))
        assertTrue(workspace.write("ok.txt", "safe").startsWith("saved"))
    }

    @Test fun shellPolicyUsesOnlyReadOnlyArgvCommandsAndWorkspacePaths() {
        val root = File(context.filesDir, "orion-python/workspace").absolutePath
        assertNotNull(OrionPythonShellPolicy.parse("""["ls"]""", root))
        assertNotNull(OrionPythonShellPolicy.parse("""["cat","notes.txt"]""", root))
        assertNull(OrionPythonShellPolicy.parse("""["sh","-c","id"]""", root))
        assertNull(OrionPythonShellPolicy.parse("""["cat","../../etc/passwd"]""", root))
        assertNull(OrionPythonShellPolicy.parse("""["ls;rm","-rf","/"]""", root))
        assertNull(OrionPythonShellPolicy.parse("""["rm","notes.txt"]""", root))
    }

    @Test fun networkRequiresBothPersistentScopeAndExactHostGrant() {
        OrionSensorConsent.grant(context, OrionSensorScope.NETWORK, 60_000L, foregroundOnly = false)
        assertNull(OrionPythonNetworkPolicy.validate(context, "https://localhost/status"))
        assertTrue(OrionPythonNetworkPolicy.grantHost(context, "localhost"))
        // Loopback is permitted only as an explicitly registered local endpoint.
        assertNotNull(OrionPythonNetworkPolicy.validate(context, "https://localhost/status"))
        assertNull(OrionPythonNetworkPolicy.validate(context, "https://other.localhost/status"))
        assertNull(OrionPythonNetworkPolicy.validate(context, "http://localhost/status"))
        assertNull(OrionPythonNetworkPolicy.validate(context, "https://user:pass@localhost/status"))
    }

    @Test fun plainHttpIsLimitedToExplicitlyGrantedLocalEndpoints() {
        OrionSensorConsent.grant(context, OrionSensorScope.NETWORK, 60_000L, foregroundOnly = false)
        assertTrue(OrionPythonNetworkPolicy.grantHost(context, "localhost", allowPlainHttp = true, allowWrites = true))
        val endpoint = OrionPythonNetworkPolicy.validate(context, "http://localhost:8123/api/")
        assertNotNull(endpoint)
        assertTrue(endpoint!!.allowWrites)
        assertNull(OrionPythonNetworkPolicy.validate(context, "http://example.com/api/"))
    }
    @Test fun readOnlyPhoneCapabilitiesDoNotTriggerPerCallApprovalAndStillCheckGrants() {
        var prompts = 0
        val api = OrionPythonPhoneApi(context) { prompts++; true }
        assertTrue(api.battery().contains("level_percent"))
        assertTrue(api.location().contains("error"))
        assertTrue(api.notifications().contains("error"))
        assertTrue(api.contacts("King").contains("error"))
        assertTrue(api.accessibility_screen().contains("error"))
        assertTrue(api.fs_write("session/log.txt", "trace").startsWith("saved"))
        assertEquals("trace", api.fs_read("session/log.txt"))
        assertEquals(0, prompts)
    }

    @Test fun readOnlyAutonomyProfileDisablesPythonMutations() {
        ToolPermissions.applyLevel(context, "read_only")
        val api = OrionPythonPhoneApi(context)
        assertTrue(api.fs_list().isNotBlank())
        assertTrue(api.fs_write("blocked.txt", "no").contains("disabled"))
        assertFalse(File(context.filesDir, "orion-python/workspace/blocked.txt").exists())
    }

}
