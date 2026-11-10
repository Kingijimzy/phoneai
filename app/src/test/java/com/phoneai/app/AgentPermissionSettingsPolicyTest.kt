package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPermissionSettingsPolicyTest {
    @Test fun explicitToolDenyOverridesTheDefaultAllowPreset() {
        assertEquals(
            ToolMode.DENY,
            ToolPermissionPolicy.effectiveMode("balanced", mapOf("shell" to ToolMode.DENY), "shell")
        )
    }

    @Test fun explicitToolAllowOverridesReadOnlyPreset() {
        assertEquals(
            ToolMode.ALLOW,
            ToolPermissionPolicy.effectiveMode("read_only", mapOf("read_screen" to ToolMode.ALLOW), "read_screen")
        )
    }

    @Test fun readOnlyPresetDeniesRegisteredWriteTools() {
        assertEquals(
            ToolMode.DENY,
            ToolPermissionPolicy.effectiveMode("read_only", emptyMap(), "open_app")
        )
    }

    @Test fun ownerCanDisableARegisteredCapabilityPack() {
        assertFalse(AgentPolicy.packEnabled("phone_control", false))
        assertTrue(AgentPolicy.packEnabled("phone_control", true))
    }

    @Test fun unsetPackUsesDeclaredDefaultAndUnknownPackFailsClosed() {
        assertTrue(AgentPolicy.packEnabled("orion_core", null))
        assertFalse(AgentPolicy.packEnabled("proactive", null))
        assertFalse(AgentPolicy.packEnabled("made_up_pack", true))
    }
}
