package com.phoneai.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolPermissionsPersistenceTest {
    private lateinit var context: Context
    private lateinit var writeTool: String

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("phoneai", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("phoneai_agent_policy", Context.MODE_PRIVATE).edit().clear().commit()
        writeTool = ToolRegistry.all.first {
            it.access == ToolAccess.WRITE && it.name !in ToolPermissionPolicy.alwaysAllowed
        }.name
    }

    @Test fun defaultPermissionIsAllow() {
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTool))
    }

    @Test fun explicitDenyPersistsAcrossPresetChanges() {
        ToolPermissions.applyLevel(context, "careful")
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTool))
        ToolPermissions.cycle(context, writeTool) // ALLOW -> DENY
        assertEquals(ToolMode.DENY, ToolPermissions.mode(context, writeTool))

        ToolPermissions.applyLevel(context, "autonomous")
        assertEquals(ToolMode.DENY, ToolPermissions.mode(context, writeTool))
        ToolPermissions.applyLevel(context, "read_only")
        assertEquals(ToolMode.DENY, ToolPermissions.mode(context, writeTool))
    }

    @Test fun explicitAllowOverridesReadOnlyPreset() {
        ToolPermissions.applyLevel(context, "read_only")
        assertEquals(ToolMode.DENY, ToolPermissions.mode(context, writeTool))
        ToolPermissions.cycle(context, writeTool) // DENY -> ALLOW
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTool))
        ToolPermissions.applyLevel(context, "balanced")
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTool))
    }

    @Test fun autonomousPresetDefaultsToAllowAndCycleOnlyTogglesDeny() {
        ToolPermissions.applyLevel(context, "autonomous")
        assertEquals("autonomous", ToolPermissions.level(context))
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTool))
        ToolPermissions.cycle(context, writeTool)
        assertEquals(ToolMode.DENY, ToolPermissions.mode(context, writeTool))
        assertEquals("autonomous", ToolPermissions.level(context))
    }

    @Test fun legacyAskValueMigratesToAllowWithoutPrompt() {
        context.getSharedPreferences("phoneai", Context.MODE_PRIVATE).edit()
            .putString("tool_modes", org.json.JSONObject().put(writeTool, "ASK").toString())
            .commit()
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTool))
    }

    @Test fun planModeIsTheOnlyGlobalExecutionStop() {
        val planned = AgentPolicy.gate(
            context, "trading_research", "live_sports", dangerous = false,
            toolMode = ToolMode.ALLOW
        )
        assertEquals(GatePolicy.Verdict.ALLOW, planned.verdict)

        AgentPolicy.setEnabled(context, "trading_research", true)
        val allowed = AgentPolicy.gate(
            context, "trading_research", "live_sports", dangerous = false,
            toolMode = ToolMode.ALLOW
        )
        assertEquals(GatePolicy.Verdict.ALLOW, allowed.verdict)
    }

    @Test fun legacyAskDoesNotCreateAnApprovalVerdict() {
        // ToolMode.ASK was removed - all non-trading capabilities default to ALLOW
        AgentPolicy.startTrustedSession(context, 60, setOf("trading_research"))
        AgentPolicy.stopTrustedSession(context)
        val result = AgentPolicy.gate(
            context, "trading_research", "live_sports", dangerous = false
        )
        assertEquals(GatePolicy.Verdict.ALLOW, result.verdict)
    }
}
