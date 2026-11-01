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
        assertNull(OrionPythonNetworkPolicy.validate(context, "https://api.example.com/status"))
        assertTrue(OrionPythonNetworkPolicy.grantHost(context, "api.example.com"))
        // No external DNS is needed: unapproved hosts are rejected before resolution.
        assertNotNull(OrionPythonNetworkPolicy.validate(context, "https://api.example.com/status"))
        assertNull(OrionPythonNetworkPolicy.validate(context, "https://other.example.com/status"))
        assertNull(OrionPythonNetworkPolicy.validate(context, "http://api.example.com/status"))
        assertNull(OrionPythonNetworkPolicy.validate(context, "https://user:pass@api.example.com/status"))
    }

    @Test fun plainHttpIsLimitedToExplicitlyGrantedLocalEndpoints() {
        OrionSensorConsent.grant(context, OrionSensorScope.NETWORK, 60_000L, foregroundOnly = false)
        assertTrue(OrionPythonNetworkPolicy.grantHost(context, "localhost", allowPlainHttp = true))
        assertNotNull(OrionPythonNetworkPolicy.validate(context, "http://localhost:8123/api/"))
        assertNull(OrionPythonNetworkPolicy.validate(context, "http://example.com/api/"))
    }
}
