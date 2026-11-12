package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Legacy compatibility APIs must never reintroduce per-tool permission scopes. */
class ToolPermissionOverrideTest {
    @Test fun legacyPresetsCannotCreatePerToolAskOrDenyOverrides() {
        val write = ToolRegistry.all.first { it.access == ToolAccess.WRITE && it.name !in ToolPermissionPolicy.alwaysAllowed }
        val effective = ToolPermissionPolicy.applyPreset(
            "read_only",
            mapOf(write.name to ToolMode.ASK, "another_tool" to ToolMode.DENY)
        )
        assertTrue(effective.isEmpty())
        assertEquals(ToolMode.ALLOW, ToolPermissionPolicy.modeOf(effective, write.name))
    }

    @Test fun explicitLegacyAllowDoesNotCreateAnOverrideEither() {
        val write = ToolRegistry.all.first { it.access == ToolAccess.WRITE && it.name !in ToolPermissionPolicy.alwaysAllowed }
        val effective = ToolPermissionPolicy.applyPreset(
            "read_only",
            mapOf(write.name to ToolMode.ALLOW)
        )
        assertTrue(effective.isEmpty())
        assertEquals(ToolMode.ALLOW, ToolPermissionPolicy.modeOf(effective, write.name))
    }

    @Test fun registeredConversationToolsRemainDirectlyAvailable() {
        val effective = ToolPermissionPolicy.applyPreset(
            "read_only",
            mapOf("time" to ToolMode.DENY)
        )
        assertTrue(effective.isEmpty())
        assertEquals(ToolMode.ALLOW, ToolPermissionPolicy.modeOf(effective, "time"))
    }
}
