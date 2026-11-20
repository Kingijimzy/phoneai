package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionStudyFixesTest {
    @Test fun guardWarnsOnlyOnThirdIdenticalCall() {
        OrionToolGuard.reset()
        val a = JSONObject().put("path", "x")
        assertEquals("ok", OrionToolGuard.annotate("workspace_read", a, "ok"))
        assertEquals("ok", OrionToolGuard.annotate("workspace_read", a, "ok"))
        assertTrue(OrionToolGuard.annotate("workspace_read", a, "ok").contains("[guard]"))
        assertEquals("ok", OrionToolGuard.annotate("workspace_read", JSONObject().put("path", "y"), "ok"))
        repeat(5) { assertFalse(OrionToolGuard.annotate("look", a, "ok").contains("[guard]")) }
    }

    @Test fun installedBuildComesFromVersionName() {
        assertEquals("build-412", OrionAppUpdater.tagFromVersionName("1.0.412"))
        assertNull(OrionAppUpdater.tagFromVersionName("1.0.0"))
        assertNull(OrionAppUpdater.tagFromVersionName("1.0"))
        assertNull(OrionAppUpdater.tagFromVersionName(null))
    }

    @Test fun voiceHasNoChatOnlyFork() {
        val src = java.io.File("src/main/java/com/phoneai/app/Agent.kt").readText()
        val body = src.substringAfter("private fun runLiveConversationBody").substringBefore("@Volatile private var voiceHeardTurn")
        assertTrue(body.contains("runConversation(command)"))
        assertFalse(body.contains("conversation-only"))
        assertFalse(body.contains("Never execute, plan, or call tools"))
    }
}
