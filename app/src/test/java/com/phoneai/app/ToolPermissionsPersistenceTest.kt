package com.phoneai.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        writeTool = ToolRegistry.all.first { it.access == ToolAccess.WRITE }.name
    }

    @Test fun legacyPermissionPreferencesCannotRestrictPartnerRuntime() {
        context.getSharedPreferences("phoneai", Context.MODE_PRIVATE).edit()
            .putString("tool_level", "read_only")
            .putString("tool_modes", org.json.JSONObject().put(writeTool, "DENY").toString())
            .commit()
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTool))
        ToolPermissions.applyLevel(context, "careful")
        ToolPermissions.cycle(context, writeTool)
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTool))
    }

    @Test fun implementedCapabilityPacksCannotBeDisabledByLegacySettings() {
        AgentPolicy.setEnabled(context, "phone_control", false)
        assertTrue(AgentPolicy.enabled(context, "phone_control"))
        AgentPolicy.setEnabled(context, "phone_control", true)
        assertTrue(AgentPolicy.enabled(context, "phone_control"))
    }

    @Test fun legacyPlanModeCannotStopExecution() {
        AgentPolicy.setMode(context, AgentPolicy.Mode.PLAN)
        val result = AgentPolicy.gate(context, "orion_core", "open_app", dangerous = false,
            toolMode = ToolMode.DENY, askedThisTask = false)
        assertEquals(GatePolicy.Verdict.ALLOW, result.verdict)
    }

    @Test fun tradingAndDestructiveConfirmationsRemainSeparateFromGenericModes() {
        val trading = AgentPolicy.gate(context, "trading_research", "paper_trade", dangerous = true)
        assertEquals(GatePolicy.Verdict.ALLOW, trading.verdict)
        assertTrue(AgentHooks.requiresConfirmation("paper_trade", org.json.JSONObject()))
        assertTrue(AgentHooks.requiresConfirmation("workspace_delete", org.json.JSONObject()))
    }
}
