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
        assertTrue(OrionDevspace.setupScript("base")!!.startsWith("set -e;"))
        assertFalse(OrionDevspace.setupScript("base")!!.contains("| tail"))
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
        assertTrue(OrionUbuntu.setupScript("base")!!.startsWith("set -e;"))
        assertFalse(OrionUbuntu.setupScript("base")!!.contains("| grep"))
        assertTrue(OrionUbuntu.setupScript("base")!!.contains("apt-get update; apt-get install"))
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

class OrionStudioEditorTest {
    @Test fun highlightsKeywordsStringsCommentsNumbers() {
        val src = "def f(x):\n    return \"hi\" + 42  # note\n"
        val toks = OrionStudioEditor.highlight("python", src).map { it.tok to src.substring(it.start, it.end) }
        assertTrue(toks.contains(OrionStudioEditor.Tok.KEYWORD to "def"))
        assertTrue(toks.contains(OrionStudioEditor.Tok.KEYWORD to "return"))
        assertTrue(toks.contains(OrionStudioEditor.Tok.STRING to "\"hi\""))
        assertTrue(toks.contains(OrionStudioEditor.Tok.NUMBER to "42"))
        assertTrue(toks.any { it.first == OrionStudioEditor.Tok.COMMENT && it.second.startsWith("# note") })
        assertEquals("go", OrionStudioEditor.langOf("a/b/main.go"))
        assertTrue(OrionStudioEditor.highlight("text", "if 1").none { it.tok == OrionStudioEditor.Tok.KEYWORD })
    }
    @Test fun parsesDiagnosticsFromCommonTools() {
        val out = "/workspace/app.py:3:8: F401 [*] `os` imported but unused\n" +
            "  /workspace/app.py:9:5 - error: \"x\" is not defined\n" +
            "app.ts(4,10): error TS2304: Cannot find name 'y'.\n" +
            "./main.go:5:2: undefined: z\n" +
            "noise line\n"
        val d = OrionStudioEditor.parseDiagnostics(out)
        assertEquals(4, d.size)
        assertEquals(3, d[0].line); assertEquals(8, d[0].col); assertTrue(d[0].message.contains("F401"))
        assertEquals("error", d[1].severity); assertEquals(9, d[1].line)
        assertEquals(4, d[2].line); assertEquals(10, d[2].col)
        assertEquals(5, d[3].line)
    }
    @Test fun cursorMath() {
        val t = "ab\ncde\nf"
        assertEquals(4, OrionStudioEditor.offsetOf(t, 2, 2))
        assertEquals(2 to 1, OrionStudioEditor.position(t, 4))
        assertEquals("fo", OrionStudioEditor.wordBefore("x = fo", 6))
    }
}

class OrionArtifactCardsTest {
    @Test fun codeFilesAndPreviewsBecomeCards() {
        val text = "Done. Here is it:\n```python\nprint(1)\nprint(2)\n```\nSaved to /workspace/app/main.py and serving at http://127.0.0.1:8080/index.html."
        val cards = OrionArtifactCards.extract(text)
        assertTrue(cards.any { it.kind == OrionArtifactCards.Kind.CODE && it.title.contains("python") && it.title.contains("2 lines") })
        assertTrue(cards.any { it.kind == OrionArtifactCards.Kind.FILE && it.target == "app/main.py" })
        assertTrue(cards.any { it.kind == OrionArtifactCards.Kind.PREVIEW && it.target == "http://127.0.0.1:8080/index.html" })
        assertTrue(OrionArtifactCards.extract("just words, no artifacts").isEmpty())
        assertTrue(OrionArtifactCards.extract("see /workspace/../etc/passwd.txt").none { it.target.contains("..") })
        assertFalse(OrionPanelText.clean(text).contains("print(1)"))
    }
}

class OrionStudioEcosystemTest {
    @Test fun discoveryParsersAndUrls() {
        val vsx = "{\"extensions\":[{\"namespace\":\"ms-python\",\"name\":\"python\",\"version\":\"1.0\",\"displayName\":\"Python\",\"description\":\"d\",\"downloadCount\":5}]}"
        assertTrue(OrionStudio.parseDiscovery("openvsx", vsx).first().startsWith("ms-python.python"))
        val npm = "{\"objects\":[{\"package\":{\"name\":\"left-pad\",\"version\":\"1.3.0\",\"description\":\"pads\"}}]}"
        assertTrue(OrionStudio.parseDiscovery("npm", npm).first().startsWith("npm:left-pad"))
        val mcp = "{\"servers\":[{\"name\":\"io.x/y\",\"description\":\"d\",\"packages\":[{\"registry_type\":\"npm\",\"identifier\":\"@x/y\"}]}]}"
        assertTrue(OrionStudio.parseDiscovery("mcp", mcp).first().contains("npm:@x/y"))
        assertTrue(OrionStudio.parseDiscovery("npm", "not json").isEmpty())
        assertNull(OrionStudio.discoverUrl("other", "q"))
        assertTrue(OrionStudio.discoverUrl("openvsx", "py thon")!!.contains("py+thon"))
    }
    @Test fun vsxAndMcpScriptsAreSafe() {
        assertNull(OrionStudio.vsxInstallScript("bad id; rm"))
        assertTrue(OrionStudio.vsxInstallScript("ms-python.python")!!.contains("open-vsx.org/api/ms-python/python/latest"))
        val s = OrionStudio.mcpScript("mcp-server-memory", "tools/list", org.json.JSONObject())!!
        assertTrue(s.contains("ORION_MCP_CMD='mcp-server-memory'") && s.contains("initialize"))
        assertNull(OrionStudio.mcpScript("x", "resources/read", org.json.JSONObject()))
        assertNull(OrionStudio.mcpScript("a\nb", "tools/list", org.json.JSONObject()))
        assertTrue(OrionStudio.fileDiagnosticsScript("a.py")!!.contains("ruff"))
        assertNull(OrionStudio.fileDiagnosticsScript("a.unknown"))
        assertNull(OrionStudio.hoverScript("a.js", 1, 0))
        assertTrue(OrionStudio.installCommand("m", "mcp", "npm:@x/y", "npx -y @x/y")!!.first.run == "npx -y @x/y")
    }
}


class OrionStudioInstallTest {
    @Test fun installScriptsEnsurePrerequisitesAndNeverSourceUnguarded() {
        val npm = OrionStudio.installScript("npm install -g x", true)
        assertTrue(npm.contains("command -v npm") && npm.contains("apt-get install") && npm.contains("INSTALL_EXIT"))
        val pip = OrionStudio.installScript("pip install -q y", false)
        assertTrue(pip.contains("python3 -m venv") && pip.contains("apk add"))
        assertTrue(pip.contains("/workspace/.venvs/studio/bin/python -m pip install -q y"))
        assertFalse(pip.contains("python3 -m pip install -q y"))
        assertTrue(pip.contains("/workspace/.venvs/studio/bin/python -m pip --version"))
        assertFalse(pip.contains("pip check"))
        val ubuntuPip = OrionStudio.installScript("pip install -q requests", true)
        assertTrue(ubuntuPip.contains("python3-full") && ubuntuPip.contains("/workspace/.venvs/studio/bin/python -m pip install -q requests"))
        assertFalse(ubuntuPip.contains("python3 -m pip install -q requests"))
        assertTrue(OrionStudio.packageScript("pip", "install", listOf("requests"), true)!!
            .contains("/workspace/.venvs/studio/bin/python -m pip install -q"))
        assertTrue(OrionStudio.VENV.contains("[ -f /workspace/.venvs/studio/bin/activate ] &&"))
        assertFalse(OrionStudio.diagnosticsScript("app")!!.contains(". /workspace/.venvs/studio/bin/activate 2>/dev/null"))
    }
}

class OrionStudioWorkspaceTest {
    @Test fun reportsRealResultsFromBuildAndTestOutput() {
        val py = OrionStudio.report("===== 12 passed, 2 failed in 1.2s =====\nTEST_EXIT=1", "test")
        assertFalse(py.ok); assertTrue(py.summary.contains("12 passed") && py.summary.contains("2 failed") && py.summary.contains("exit 1"))
        val good = OrionStudio.report("ok  \texample/pkg\t0.2s\nBUILD_EXIT=0", "build")
        assertTrue(good.ok); assertTrue(good.summary.contains("succeeded"))
        assertTrue(OrionStudio.report("cargo\ntest result: ok. 5 passed; 0 failed", "test").summary.contains("no exit status"))
        assertTrue(OrionStudio.buildScript("app", "test", "")!!.contains("TEST_EXIT="))
        assertFalse(OrionStudio.buildScript("app", "build", "")!!.contains("| tail -n 40"))
    }
    @Test fun packageManagerScriptsAreValidatedAndRuntimeAware() {
        assertTrue(OrionStudio.packageScript("auto", "install", listOf("git", "curl"), true)!!.contains("apt-get install"))
        assertTrue(OrionStudio.packageScript("auto", "install", listOf("git"), false)!!.contains("apk add"))
        assertTrue(OrionStudio.packageScript("apk", "install", listOf("git"), true)!!.contains("exit 1"))
        assertNull(OrionStudio.packageScript("apt", "install", listOf("bad; rm -rf /"), true))
        assertNull(OrionStudio.packageScript("apt", "install", emptyList(), true))
        assertNull(OrionStudio.packageScript("apt", "search", listOf("a", "b"), true))
        assertTrue(OrionStudio.packageScript("pip", "list", emptyList(), true)!!.contains("pip list"))
        assertTrue(OrionStudio.packageScript("npm", "update", emptyList(), true)!!.contains("npm update -g"))
        assertNull(OrionStudio.packageScript("gem", "list", emptyList(), true))
    }
    @Test fun recoveryScriptsRejectUnsafeNames() {
        assertTrue(OrionStudio.snapshotScript("app", "before fix!")!!.contains("before-fix-"))
        assertNull(OrionStudio.snapshotScript("../x", "a"))
        assertNull(OrionStudio.restoreScript("../../etc/passwd"))
        assertNull(OrionStudio.restoreScript("a.zip"))
        assertTrue(OrionStudio.restoreScript("app-1.tar.gz")!!.contains("pre-restore"))
        assertTrue(OrionStudio.rollbackScript("app", "HEAD")!!.contains("git stash push"))
        assertNull(OrionStudio.rollbackScript("app", "--hard"))
    }
    @Test fun devAgentPromptCarriesTheFullLoop() {
        val p = OrionStudio.devPrompt("Build a notes API", "notes", "snap.tar.gz")
        listOf("INSPECT", "PLAN", "EDIT", "EXECUTE", "DEBUG", "VERIFY", "/workspace/notes", "snap.tar.gz", "Build a notes API").forEach { assertTrue(it, p.contains(it)) }
        assertTrue(OrionStudio.debugScript("a.py", listOf(3), emptyList())!!.contains("pp locals()"))
    }
}
