package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionRunLogTest {
    @Test fun titlesNeverNameTheRuntime() {
        val runtimes = listOf("execute_system_op", "shell", "python_exec", "dev_shell", "termux_command")
        runtimes.forEach { t ->
            val title = OrionRunLog.title(t, JSONObject())
            assertFalse(title, title.contains("python", true) || title.contains("shizuku", true) || title.contains("linux", true) || title.contains("shell", true))
        }
        assertEquals("Working with reddit", OrionRunLog.title("social", JSONObject().put("platform", "reddit")))
    }

    @Test fun detailPicksTheMostUsefulArgument() {
        assertEquals("ls -la", OrionRunLog.detail(JSONObject().put("command", "ls   -la")))
        assertEquals("kotlin", OrionRunLog.detail(JSONObject().put("query", "kotlin")))
        assertEquals("", OrionRunLog.detail(null))
        assertEquals(90, OrionRunLog.detail(JSONObject().put("text", "x".repeat(300))).length)
    }

    @Test fun stepLifecycleFeedsNodes() {
        OrionRunLog.clear(); OrionNodes.reset()
        val id = OrionRunLog.begin("python_exec", JSONObject().put("code", "print(1)"), now = 1_000L)
        assertEquals(1, OrionRunLog.runningCount())
        OrionRunLog.finish(id, "1", false, now = 1_450L)
        val s = OrionRunLog.steps.single()
        assertEquals(OrionRunLog.Status.DONE, s.status)
        assertEquals(450L, OrionRunLog.durationMs(s, 9_999L))
        assertEquals("450ms", OrionRunLog.durationLabel(450))
        assertEquals(1, OrionNodes.pulses["shell"]?.count)
        val bad = OrionRunLog.begin("browser_click", null)
        OrionRunLog.finish(bad, "error: no element", true)
        assertTrue(OrionNodes.pulses["web"]!!.failed)
    }

    @Test fun nodesOpenTheirPages() {
        OrionNodes.all.forEach { assertTrue(it.id, OrionPages.find(OrionNodes.pageFor(it.id)) != null) }
    }
}
