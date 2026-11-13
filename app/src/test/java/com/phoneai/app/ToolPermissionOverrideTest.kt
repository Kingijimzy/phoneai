package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ToolPermissionOverrideTest {
    @Test fun presetsApplyExpectedWritePolicies() {
        val write = ToolRegistry.all.first { it.access == ToolAccess.WRITE && it.name !in ToolPermissionPolicy.alwaysAllowed }
        assertEquals(ToolMode.DENY, ToolPermissionPolicy.preset("read_only")[write.name])
        assertEquals(ToolMode.ASK, ToolPermissionPolicy.preset("careful")[write.name])
        assertEquals(ToolMode.ALLOW, ToolPermissionPolicy.preset("autonomous")[write.name])
    }

    @Test fun explicitOverridesWinAndUnknownToolsAreIgnored() {
        val write = ToolRegistry.all.first { it.access == ToolAccess.WRITE && it.name !in ToolPermissionPolicy.alwaysAllowed }
        val effective = ToolPermissionPolicy.applyPreset("read_only",
            mapOf(write.name to ToolMode.ALLOW, "not_registered" to ToolMode.DENY))
        assertEquals(ToolMode.ALLOW, effective[write.name])
        assertFalse(effective.containsKey("not_registered"))
    }

    @Test fun modesRoundTripThroughSerialization() {
        val original = mapOf("shell" to ToolMode.DENY, "workspace_write" to ToolMode.ASK)
        assertEquals(original, ToolPermissionPolicy.parse(ToolPermissionPolicy.serialize(original)))
    }

    @Test fun cycleMovesAllowAskDeny() {
        assertEquals(ToolMode.ASK, ToolPermissionPolicy.next(ToolMode.ALLOW))
        assertEquals(ToolMode.DENY, ToolPermissionPolicy.next(ToolMode.ASK))
        assertEquals(ToolMode.ALLOW, ToolPermissionPolicy.next(ToolMode.DENY))
    }
}
