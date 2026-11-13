package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ToolPermissionOverrideTest {
    @Test fun everyLegacyPresetProducesNoPerToolRestriction() {
        val tools = listOf("read_only", "careful", "balanced", "autonomous")
        tools.forEach { level ->
            assertTrue(ToolPermissionPolicy.preset(level).isEmpty())
            assertEquals(ToolMode.ALLOW, ToolPermissionPolicy.modeOf(mapOf("shell" to ToolMode.DENY), "shell"))
        }
    }

    @Test fun legacyOverridesCannotCreateAskOrDenyRules() {
        val write = ToolRegistry.all.first { it.access == ToolAccess.WRITE && it.name !in ToolPermissionPolicy.alwaysAllowed }
        val effective = ToolPermissionPolicy.applyPreset("read_only",
            mapOf(write.name to ToolMode.DENY, "not_registered" to ToolMode.ASK))
        assertTrue(effective.isEmpty())
        assertEquals(ToolMode.ALLOW, ToolPermissionPolicy.modeOf(effective, write.name))
    }

    @Test fun legacyStoredValuesCanBeParsedButAreNeverEffective() {
        val parsed = ToolPermissionPolicy.parse(ToolPermissionPolicy.serialize(mapOf("shell" to ToolMode.DENY)))
        assertEquals(ToolMode.DENY, parsed["shell"])
        assertEquals(ToolMode.ALLOW, ToolPermissionPolicy.modeOf(parsed, "shell"))
    }

    @Test fun legacyCycleCannotChangeEffectivePermission() {
        assertEquals(ToolMode.ALLOW, ToolPermissionPolicy.next(ToolMode.ALLOW))
        assertEquals(ToolMode.ALLOW, ToolPermissionPolicy.next(ToolMode.ASK))
        assertEquals(ToolMode.ALLOW, ToolPermissionPolicy.next(ToolMode.DENY))
    }
}
