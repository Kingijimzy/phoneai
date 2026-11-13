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
        writeTool = ToolRegistry.all.first { it.access == ToolAccess.WRITE && it.name !in ToolPermissionPolicy.alwaysAllowed }.name
    }

    @Test fun defaultPermissionIsAllow() {
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTool))
    }

    @Test fun explicitDenyPersistsAndIsRespected() {
        ToolPermissions.set(context, mapOf(writeTool to ToolMode.DENY))
        assertEquals(ToolMode.DENY, ToolPermissions.mode(context, writeTool))
    }

    @Test fun readOnlyPresetDeniesWriteTools() {
        ToolPermissions.applyLevel(context, "read_only")
        assertEquals("read_only", ToolPermissions.level(context))
        assertEquals(ToolMode.DENY, ToolPermissions.mode(context, writeTool))
    }

    @Test fun carefulPresetRequiresApprovalForWriteTools() {
        ToolPermissions.applyLevel(context, "careful")
        assertEquals(ToolMode.ASK, ToolPermissions.mode(context, writeTool))
    }

    @Test fun cyclingPermissionPersists() {
        ToolPermissions.cycle(context, writeTool)
        assertEquals(ToolMode.ASK, ToolPermissions.mode(context, writeTool))
        ToolPermissions.cycle(context, writeTool)
        assertEquals(ToolMode.DENY, ToolPermissions.mode(context, writeTool))
    }

    @Test fun planModeReturnsPlanOnly() {
        AgentPolicy.setMode(context, AgentPolicy.Mode.PLAN)
        AgentPolicy.setEnabled(context, "trading_research", true)
        val result = AgentPolicy.gate(context, "trading_research", "live_sports",
            dangerous = false, toolMode = ToolMode.ALLOW, askedThisTask = false)
        assertEquals(GatePolicy.Verdict.PLAN_ONLY, result.verdict)
    }

    @Test fun disabledPackCannotDispatch() {
        AgentPolicy.setMode(context, AgentPolicy.Mode.ALLOW)
        AgentPolicy.setEnabled(context, "trading_research", false)
        val result = AgentPolicy.gate(context, "trading_research", "live_sports",
            dangerous = false, toolMode = ToolMode.ALLOW, askedThisTask = true)
        assertEquals(GatePolicy.Verdict.DENY, result.verdict)
    }
}
