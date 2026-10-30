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
        writeTool = ToolRegistry.all.first {
            it.access == ToolAccess.WRITE && it.name !in ToolPermissionPolicy.alwaysAllowed
        }.name
    }

    @Test fun perToolOverrideSurvivesGlobalModeChanges() {
        ToolPermissions.applyLevel(context, "careful")
        assertEquals(ToolMode.ASK, ToolPermissions.mode(context, writeTool))

        ToolPermissions.cycle(context, writeTool) // ASK -> DENY
        assertEquals(ToolMode.DENY, ToolPermissions.mode(context, writeTool))

        ToolPermissions.applyLevel(context, "read_only")
        assertEquals(ToolMode.DENY, ToolPermissions.mode(context, writeTool))

        ToolPermissions.applyLevel(context, "autonomous")
        assertEquals(ToolMode.DENY, ToolPermissions.mode(context, writeTool))
    }

    @Test fun explicitAllowOverrideSurvivesRestrictivePreset() {
        ToolPermissions.applyLevel(context, "read_only")
        assertEquals(ToolMode.DENY, ToolPermissions.mode(context, writeTool))

        ToolPermissions.cycle(context, writeTool) // DENY -> ALLOW
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTool))

        ToolPermissions.applyLevel(context, "careful")
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTool))
    }

    @Test fun autonomousBaseRemainsActiveAfterPerToolOverride() {
        ToolPermissions.applyLevel(context, "autonomous")
        assertEquals("autonomous", ToolPermissions.level(context))

        ToolPermissions.cycle(context, writeTool) // ALLOW -> ASK
        assertEquals("autonomous", ToolPermissions.level(context))
        assertEquals(ToolMode.ASK, ToolPermissions.mode(context, writeTool))
    }

    @Test fun explicitAskOverrideStillPromptsUnderAutonomousPreset() {
        ToolPermissions.applyLevel(context, "autonomous")
        ToolPermissions.cycle(context, writeTool) // ALLOW -> ASK
        assertEquals(true, ToolPermissions.hasOverride(context, writeTool))

        AgentPolicy.setEnabled(context, "phone_control", true)
        AgentPolicy.setMode(context, AgentPolicy.Mode.ASK)
        val result = AgentPolicy.gate(
            context, "phone_control", writeTool, dangerous = false,
            toolMode = ToolPermissions.mode(context, writeTool), askedThisTask = false
        )
        assertEquals(GatePolicy.Verdict.ASK_TOOL, result.verdict)
    }

    @Test fun deterministicReadCannotRunInPlanMode() {
        ToolPermissions.applyLevel(context, "balanced")
        AgentPolicy.setEnabled(context, "trading_research", true)
        AgentPolicy.setMode(context, AgentPolicy.Mode.PLAN)

        val result = AgentPolicy.gate(
            context, "trading_research", "live_sports", dangerous = false,
            toolMode = ToolPermissions.mode(context, "live_sports"), askedThisTask = false
        )
        assertEquals(GatePolicy.Verdict.PLAN_ONLY, result.verdict)
    }

    @Test fun deterministicReadHonoursExplicitAskOverride() {
        ToolPermissions.applyLevel(context, "balanced")
        ToolPermissions.cycle(context, "live_sports") // ALLOW -> ASK
        AgentPolicy.setEnabled(context, "trading_research", true)
        AgentPolicy.setMode(context, AgentPolicy.Mode.ASK)

        val result = AgentPolicy.gate(
            context, "trading_research", "live_sports", dangerous = false,
            toolMode = ToolPermissions.mode(context, "live_sports"), askedThisTask = false
        )
        assertEquals(GatePolicy.Verdict.ASK_TOOL, result.verdict)
    }

    @Test fun legacyCustomToolMapIsMigratedAsOverrides() {
        context.getSharedPreferences("phoneai", Context.MODE_PRIVATE).edit()
            .putString("tool_level", "custom")
            .putString("tool_modes", org.json.JSONObject().put(writeTool, "DENY").toString())
            .commit()

        ToolPermissions.applyLevel(context, "balanced")
        assertEquals(ToolMode.DENY, ToolPermissions.mode(context, writeTool))
    }
}
