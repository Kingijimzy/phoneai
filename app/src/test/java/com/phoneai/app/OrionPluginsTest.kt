package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionPluginsTest {
    private val manifest = """{"id":"git-tools","version":"1.0.0","description":"git helpers","tools":[
        {"name":"log","description":"recent commits","command":"cd /workspace/{project} && git log --oneline -n {count}","timeout_sec":60}]}"""

    @Test fun validManifestParses() {
        val (p, why) = OrionPlugins.parse(manifest)
        assertNotNull(why, p)
        assertEquals("git-tools", p!!.id)
        assertEquals(60, p.tools.single().timeoutSec)
    }

    @Test fun badManifestsAreRefusedWithAReason() {
        assertTrue(OrionPlugins.parse("nope").second.contains("JSON"))
        assertTrue(OrionPlugins.parse("""{"id":"X!","tools":[]}""").second.contains("id"))
        assertTrue(OrionPlugins.parse("""{"id":"ok-id","tools":[]}""").second.contains("1-"))
        assertTrue(OrionPlugins.parse("""{"id":"ok-id","tools":[{"name":"Bad Name","command":"ls"}]}""").second.contains("tool name"))
        assertTrue(OrionPlugins.parse("""{"id":"ok-id","tools":[{"name":"a1","command":"ls"},{"name":"a1","command":"ls"}]}""").second.contains("unique"))
    }

    @Test fun argumentsAreQuotedSoTheyCannotInjectCommands() {
        val tool = OrionPlugins.parse(manifest).first!!.tools.single()
        val (cmd, _) = OrionPlugins.render(tool, JSONObject().put("project", "app; rm -rf /").put("count", "5"))
        assertEquals("cd /workspace/'app; rm -rf /' && git log --oneline -n '5'", cmd)
        val (none, missing) = OrionPlugins.render(tool, JSONObject().put("project", "x"))
        assertNull(none); assertEquals("count", missing)
    }

    @Test fun pluginToolIsRegisteredForTheCoordinator() {
        assertNotNull(ToolRegistry.find("plugin"))
        assertEquals(ToolAccess.WRITE, ToolRegistry.access("plugin"))
    }
}
