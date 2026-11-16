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

class OrionUbuntuTest {
    private val sha = "a".repeat(64)
    @Test fun picksNewestArm64BaseImageFromCanonicalSums() {
        val sums = "$sha *ubuntu-base-24.04-base-arm64.tar.gz\n${"b".repeat(64)} *ubuntu-base-24.04.3-base-arm64.tar.gz\n" +
            "${"c".repeat(64)} *ubuntu-base-24.04.3-base-amd64.tar.gz\nbad *ubuntu-base-24.04.9-base-arm64.tar.gz\n"
        val (name, hash) = OrionUbuntu.pick(sums)!!
        assertEquals("ubuntu-base-24.04.3-base-arm64.tar.gz", name)
        assertEquals("b".repeat(64), hash)
        assertNull(OrionUbuntu.pick("nothing here"))
    }
    @Test fun manifestIsValidAndVersioned() {
        val m = OrionUbuntu.manifestFor("ubuntu-base-24.04.3-base-arm64.tar.gz", sha, 30_000_000L)
        assertNull(LinuxRuntimePolicy.validate(m))
        assertTrue(m.version.startsWith("ubuntu-24.04.3-"))
    }
    @Test fun aptProfilesAndRouting() {
        assertTrue(OrionUbuntu.setupScript("base")!!.contains("build-essential"))
        assertTrue(OrionUbuntu.setupScript("all")!!.contains("redis-server"))
        assertNull(OrionUbuntu.setupScript("nope"))
        assertEquals(ShellExecutionRouting.Backend.UBUNTU, ShellExecutionRouting.backend("Ubuntu"))
        assertTrue(ShellExecutionRouting.isAptOperation("cd x && apt-get install -y git"))
        assertEquals(600_000L, ShellExecutionRouting.commandTimeoutMs("apt-get update"))
    }
}

class OrionStudioTest {
    @Test fun pathsAndRefsAreValidated() {
        assertTrue(OrionStudio.validPath("proj/src/a.py"))
        assertFalse(OrionStudio.validPath("../etc/passwd"))
        assertFalse(OrionStudio.validPath("/abs"))
        assertFalse(OrionStudio.validRef("--upload-pack=x"))
        assertNull(OrionStudio.diagnosticsScript("a b"))
        assertNull(OrionStudio.completeScript("x.py", 0, 0))
    }
    @Test fun gitBuildAndDebugScripts() {
        val o = org.json.JSONObject().put("message", "it's done")
        val commit = OrionStudio.gitScript("commit", "app", o)!!
        assertTrue(commit.contains("'it'\\''s done'"))
        assertNull(OrionStudio.gitScript("commit", "app", org.json.JSONObject()))
        assertNull(OrionStudio.gitScript("rm", "app", org.json.JSONObject()))
        assertNull(OrionStudio.gitScript("checkout", "app", org.json.JSONObject().put("ref", "--force")))
        assertTrue(OrionStudio.buildScript("app", "build", "")!!.contains("BUILD_EXIT"))
        assertTrue(OrionStudio.debugScript("a.py", listOf(3), emptyList())!!.contains("pdb"))
        assertNull(OrionStudio.debugScript("a.py", listOf(0), emptyList()))
        assertNull(OrionStudio.cloneScript("http://x/y", "p"))
        assertTrue(OrionStudio.cloneScript("https://github.com/a/b", "p")!!.contains("git clone"))
    }
    @Test fun registryAndPreview() {
        assertTrue(OrionStudio.catalog.map { it.id }.toSet().size == OrionStudio.catalog.size)
        assertTrue(OrionStudio.catalog.any { it.kind == OrionStudio.Kind.MCP })
        assertNull(OrionStudio.installCommand("x", "plugin", "npm:bad pkg; rm -rf /"))
        assertTrue(OrionStudio.installCommand("mine", "plugin", "pip:requests")!!.second.contains("pip install"))
        assertNull(OrionStudio.previewCommand("app", 80, ""))
        assertTrue(OrionStudio.previewCommand("app", 8080, "")!!.contains("http.server"))
        assertTrue(OrionStudio.toolchainScript("go", true)!!.contains("golang-go"))
        assertTrue(OrionStudio.toolchainScript("go", false)!!.contains("apk add"))
        assertNull(OrionStudio.toolchainScript("cobol", true))
    }
}
