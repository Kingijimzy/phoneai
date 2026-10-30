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

    @Test fun legacyCustomToolMapIsMigratedAsOverrides() {
        context.getSharedPreferences("phoneai", Context.MODE_PRIVATE).edit()
            .putString("tool_level", "custom")
            .putString("tool_modes", org.json.JSONObject().put(writeTool, "DENY").toString())
            .commit()

        ToolPermissions.applyLevel(context, "balanced")
        assertEquals(ToolMode.DENY, ToolPermissions.mode(context, writeTool))
    }
}
