package com.phoneai.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        writeTool = ToolRegistry.all.first { it.access == ToolAccess.WRITE && it.name !in ToolPermissionPolicy.alwaysAllowed }.name
    }

    @Test fun defaultPermissionIsAllow() {
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTool))
    }

    @Test fun explicitLegacyDenyIsDiscardedAndNeverEffective() {
        ToolPermissions.set(context, mapOf(writeTool to ToolMode.DENY))
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTool))
        assertTrue(ToolPermissions.map(context).isEmpty())
    }

    @Test fun readOnlyAndCarefulPresetsNormalizeToAutonomous() {
        ToolPermissions.applyLevel(context, "read_only")
        assertEquals("autonomous", ToolPermissions.level(context))
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTool))
        ToolPermissions.applyLevel(context, "careful")
        assertEquals("autonomous", ToolPermissions.level(context))
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTool))
    }

    @Test fun cyclingCannotCreateAnInternalToolBlock() {
        ToolPermissions.cycle(context, writeTool)
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTool))
        ToolPermissions.cycle(context, writeTool)
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTool))
    }

    @Test fun legacyPlanModeCannotStopRegisteredToolDispatch() {
        AgentPolicy.setMode(context, AgentPolicy.Mode.PLAN)
        val result = AgentPolicy.gate(context, "trading_research", "live_sports",
            dangerous = false, toolMode = ToolMode.DENY, askedThisTask = false,
            hardBlockReason = "legacy local blocker")
        assertEquals(GatePolicy.Verdict.ALLOW, result.verdict)
        assertEquals(AgentPolicy.Mode.ALLOW, AgentPolicy.mode(context))
    }

    @Test fun disabledPackCannotDisableRegisteredTool() {
        AgentPolicy.setEnabled(context, "trading_research", false)
        assertTrue(AgentPolicy.enabled(context, "trading_research"))
        val result = AgentPolicy.gate(context, "trading_research", "live_sports",
            dangerous = false, toolMode = ToolMode.DENY, askedThisTask = false)
        assertEquals(GatePolicy.Verdict.ALLOW, result.verdict)
    }

    @Test fun backgroundProactiveSwitchRemainsAnIndependentOptIn() {
        AgentPolicy.setEnabled(context, "proactive", false)
        assertFalse(AgentPolicy.backgroundEnabled(context, "proactive"))
        assertTrue(AgentPolicy.enabled(context, "proactive"))
    }
}
