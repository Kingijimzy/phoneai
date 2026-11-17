package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionActivityHubTest {
    private fun step(id: Long, tool: String, status: OrionRunLog.Status = OrionRunLog.Status.DONE, args: String = "", out: String = "", start: Long = id * 10) =
        OrionRunLog.Step(id, tool, OrionRunLog.title(tool, null), "", null, start, start + 5, status, out, args)

    @Test fun studioActionsOpenTheirOwnPage() {
        fun page(a: String) = (OrionActivityHub.target("studio", JSONObject().put("action", a)) as OrionActivityHub.Target.Studio).page
        assertEquals("terminal", page("terminal"))
        assertEquals("git", page("git"))
        assertEquals("extensions", page("mcp_call"))
        assertEquals("build", page("test"))
        assertEquals("recovery", page("rollback"))
        assertEquals("agent", page("dev_task"))
    }

    @Test fun codeFilesOpenInTheEditor() {
        val t = OrionActivityHub.target("dev_write", JSONObject().put("path", "app/main.py")) as OrionActivityHub.Target.Studio
        assertEquals("editor", t.page); assertEquals("app/main.py", t.file)
        assertEquals(OrionActivityHub.Lane.CODE, OrionActivityHub.lane("dev_write", null))
    }

    @Test fun webStepsLinkToTheSource() {
        val t = OrionActivityHub.target("fetch_url", JSONObject().put("url", "https://example.com/a"))
        assertEquals(OrionActivityHub.Target.Url("https://example.com/a"), t)
        val s = OrionActivityHub.target("web_search", JSONObject().put("query", "kotlin flow"))
        assertTrue((s as OrionActivityHub.Target.Url).url.contains("kotlin+flow"))
        assertEquals(listOf("https://a.dev/x", "https://b.dev"), OrionActivityHub.sources("see https://a.dev/x, and https://b.dev. also https://a.dev/x"))
    }

    @Test fun mcpAndRuntimeLanes() {
        assertEquals(OrionActivityHub.Lane.MCP, OrionActivityHub.lane("studio", JSONObject().put("action", "mcp_call")))
        assertEquals(OrionActivityHub.Lane.RUNTIME, OrionActivityHub.lane("studio", JSONObject().put("action", "packages")))
        assertEquals(OrionActivityHub.Lane.RUNTIME, OrionActivityHub.lane("shell", null))
    }

    @Test fun oneSummaryPerTaskAndInterruptions() {
        val run = listOf(step(1, "dev_write"), step(2, "shell", OrionRunLog.Status.FAILED), step(3, "fetch_url", OrionRunLog.Status.RUNNING))
        val sum = OrionActivityHub.summarize("Build the app", run)
        assertEquals(3, sum.total); assertEquals(2, sum.done); assertEquals(1, sum.failed); assertEquals(1, sum.running)
        assertEquals("running", sum.status)
        assertEquals(listOf(2L), OrionActivityHub.interruptions(run).map { it.id })
        assertEquals("failed", OrionActivityHub.summarize("x", listOf(step(1, "shell", OrionRunLog.Status.FAILED))).status)
    }

    @Test fun historyRoundTripsSearchesAndResumes() {
        val run = listOf(step(1, "dev_write", args = "{\"path\":\"a.py\"}", out = "ok"), step(2, "shell", OrionRunLog.Status.RUNNING))
        val saved = OrionActivityHistory.upsert(emptyList(), run, "Write the parser")
        val back = OrionActivityHistory.decode(OrionActivityHistory.encode(saved))
        assertEquals(1, back.size); assertEquals("running", back[0].status); assertEquals("{\"path\":\"a.py\"}", back[0].steps[0].args)
        assertTrue(OrionActivityHub.matches(back[0], "parser")); assertTrue(OrionActivityHub.matches(back[0], "shell"))
        assertTrue(!OrionActivityHub.matches(back[0], "zzz"))
        val settled = OrionActivityHistory.settle(back, null)
        assertEquals("interrupted", settled[0].status)
        assertTrue(OrionActivityHistory.resumePrompt(settled[0]).contains("Write the parser"))
        val again = OrionActivityHistory.upsert(saved, run + step(3, "fetch_url"), "Write the parser")
        assertEquals(1, again.size); assertEquals(3, again[0].steps.size)
    }
}
