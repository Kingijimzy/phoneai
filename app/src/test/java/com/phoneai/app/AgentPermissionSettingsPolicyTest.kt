package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPermissionSettingsPolicyTest {
    @Test fun everyRegisteredToolUsesPartnerOperationInsteadOfPerToolModes() {
        ToolRegistry.all.forEach { tool ->
            assertEquals("Tool ${tool.name} should not have an app-level permission gate", ToolMode.ALLOW,
                ToolPermissionPolicy.effectiveMode("read_only", mapOf(tool.name to ToolMode.DENY), tool.name))
        }
    }

    @Test fun allImplementedPacksAreAvailableAndUnknownPacksRemainUnsupported() {
        AgentPolicy.packs.filter { it.implemented }.forEach {
            assertTrue("Pack ${it.id}", AgentPolicy.packEnabled(it.id, false))
        }
        assertFalse(AgentPolicy.packEnabled("made_up_pack", true))
    }

    @Test fun legacyModeNamesNoLongerChangeTheDecision() {
        assertEquals(ToolMode.ALLOW, ToolPermissionPolicy.effectiveMode("careful", emptyMap(), "shell"))
        assertEquals(ToolMode.ALLOW, ToolPermissionPolicy.effectiveMode("read_only", emptyMap(), "open_app"))
        assertEquals("partner", "partner")
    }
}
