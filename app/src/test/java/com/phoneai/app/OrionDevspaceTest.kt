package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionDevspaceTest {
    @Test fun setupProfilesInstallRealPackages() {
        assertTrue(OrionDevspace.setupScript("base")!!.contains("python3"))
        assertTrue(OrionDevspace.setupScript("media")!!.contains("ffmpeg"))
        assertTrue(OrionDevspace.setupScript("all")!!.contains("redis"))
        assertNull(OrionDevspace.setupScript("nope"))
    }

    @Test fun namesAndPackagesAreValidatedBeforeAnythingRuns() {
        assertNull(OrionDevspace.venvScript("../x", emptyList()))
        assertNull(OrionDevspace.venvScript("ok", listOf("requests; rm -rf /")))
        assertTrue(OrionDevspace.venvScript("ok", listOf("requests==2.32.0", "fastapi[standard]"))!!.contains("VENV_READY"))
        assertNull(OrionDevspace.scaffold("bad name", "python"))
        assertNull(OrionDevspace.runScript("../etc", "", "ls"))
    }

    @Test fun scaffoldsAreRunnableProjects() {
        val api = OrionDevspace.scaffold("shop", "api")!!
        assertTrue(api.contains("server.py") && api.contains("serve_forever") && api.contains("git init"))
        assertTrue(OrionDevspace.scaffold("site", "node")!!.contains("package.json"))
        assertNull(OrionDevspace.scaffold("x", "cobol"))
    }

    @Test fun runScriptActivatesTheVenvInsideTheProject() {
        assertEquals("cd /workspace/shop && . /workspace/.venvs/v1/bin/activate && python3 main.py", OrionDevspace.runScript("shop", "v1", "python3 main.py"))
    }

    @Test fun servicesAreMarkedSoTheyCanBeStopped() {
        val w = OrionDevspace.wrapService("api", "python3 server.py", "shop")
        assertTrue(w.contains("ORION_SVC_api") && w.contains("/workspace/.services/api.log") && w.contains("cd /workspace/shop"))
        assertTrue(OrionDevspace.stopScript("api").contains("pkill -f ORION_SVC_api"))
        assertTrue(OrionDevspace.healthScript(8080).contains("127.0.0.1:8080"))
    }

    @Test fun jobsAreDueByIntervalOrDailyTime() {
        val now = 10_000_000_000L
        val every = OrionDevspace.Job("a", "x", 30, "", now - 31 * 60_000L, "")
        assertTrue(OrionDevspace.isDue(every, now, 9, 0))
        assertFalse(OrionDevspace.isDue(every.copy(lastRun = now - 5 * 60_000L), now, 9, 0))
        val daily = OrionDevspace.Job("b", "x", 0, "07:30", 0L, "")
        assertFalse(OrionDevspace.isDue(daily, now, 7, 0))
        assertTrue(OrionDevspace.isDue(daily, now, 7, 31))
        assertFalse(OrionDevspace.isDue(daily.copy(lastRun = now - 3_600_000L), now, 8, 0))
    }

    @Test fun devspaceIsRoutedAndNamed() {
        assertNotNull(ToolRegistry.find("devspace"))
        assertEquals(emptyList<String>(), OrionSelfTest.unrouted())
        assertEquals("shell", OrionNodes.nodeFor("devspace"))
    }
}
