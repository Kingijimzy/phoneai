package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPermissionOverrideTest {
    @Test fun legacyAllowAskDenyOverridesAreIgnored() {
        val tool = ToolRegistry.all.first { it.name !in ToolPermissionPolicy.alwaysAllowed }
        for (mode in ToolMode.values()) {
            val effective = ToolPermissionPolicy.applyPreset("read_only", mapOf(tool.name to mode))
            assertEquals(ToolMode.ALLOW, ToolPermissionPolicy.modeOf(effective, tool.name))
        }
    }

    @Test fun conversationToolsRemainAvailable() {
        val effective = ToolPermissionPolicy.applyPreset("read_only", mapOf("time" to ToolMode.DENY))
        assertEquals(ToolMode.ALLOW, ToolPermissionPolicy.modeOf(effective, "time"))
    }
}
