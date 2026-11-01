package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPermissionOverrideTest {
    @Test fun selectedPresetRetainsExplicitPerToolOverrides() {
        val write = ToolRegistry.all.first { it.access == ToolAccess.WRITE && it.name !in ToolPermissionPolicy.alwaysAllowed }
        val ask = ToolRegistry.all.firstOrNull { it.access == ToolAccess.READ && it.name !in ToolPermissionPolicy.alwaysAllowed }

        val effective = ToolPermissionPolicy.applyPreset(
            "read_only",
            mapOf(write.name to ToolMode.ASK)
        )

        assertEquals(ToolMode.ASK, effective[write.name])
        if (ask != null) assertTrue(ask.name !in effective)
    }

    @Test fun explicitAllowCanOverrideRestrictivePreset() {
        val write = ToolRegistry.all.first { it.access == ToolAccess.WRITE && it.name !in ToolPermissionPolicy.alwaysAllowed }
        val effective = ToolPermissionPolicy.applyPreset(
            "read_only",
            mapOf(write.name to ToolMode.ALLOW)
        )
        assertEquals(ToolMode.ALLOW, ToolPermissionPolicy.modeOf(effective, write.name))
    }

    @Test fun alwaysAllowedConversationToolsCannotBeOverridden() {
        val effective = ToolPermissionPolicy.applyPreset(
            "read_only",
            mapOf("time" to ToolMode.DENY)
        )
        assertEquals(ToolMode.ALLOW, ToolPermissionPolicy.modeOf(effective, "time"))
    }
}
