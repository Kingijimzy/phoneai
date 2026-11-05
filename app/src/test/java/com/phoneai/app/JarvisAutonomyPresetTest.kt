package com.phoneai.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class JarvisAutonomyPresetTest {
    @Test fun jarvisPresetEnablesEveryImplementedCapabilityPack() {
        val context = RuntimeEnvironment.getApplication() as Context

        AgentPolicy.enableAllImplementedPacks(context)

        AgentPolicy.packs.filter { it.implemented }.forEach { pack ->
            assertTrue("Jarvis should enable implemented pack ${pack.id}", AgentPolicy.enabled(context, pack.id))
        }
    }
    @Test fun firstBrainStartEnablesRoutineAutonomyWithoutOverridingOwnerDenials() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("phoneai", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("phoneai_agent_policy", Context.MODE_PRIVATE).edit()
            .clear()
            .putBoolean("pack_self_evolution", false)
            .commit()
        context.getSharedPreferences("orion_owner_dials", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("orion_jarvis_bootstrap", Context.MODE_PRIVATE).edit().clear().commit()

        val writeTools = ToolRegistry.all.filter {
            it.access == ToolAccess.WRITE && it.name !in ToolPermissionPolicy.alwaysAllowed
        }.take(2)
        assertEquals(2, writeTools.size)
        ToolPermissions.applyLevel(context, "autonomous")
        ToolPermissions.cycle(context, writeTools[0].name) // ALLOW -> ASK
        ToolPermissions.cycle(context, writeTools[1].name) // ALLOW -> ASK
        ToolPermissions.cycle(context, writeTools[1].name) // ASK -> DENY

        OrionJarvisBootstrap.ensureApplied(context)

        assertEquals(OrionMode.AUTOPILOT, OrionAutonomySettings.read(context).mode)
        assertEquals(ToolMode.ALLOW, ToolPermissions.mode(context, writeTools[0].name))
        assertEquals(ToolMode.DENY, ToolPermissions.mode(context, writeTools[1].name))
        assertFalse(AgentPolicy.enabled(context, "self_evolution"))
        AgentPolicy.packs.filter { it.implemented && it.id != "self_evolution" }.forEach { pack ->
            assertTrue("Unconfigured implemented pack should be enabled: ${pack.id}", AgentPolicy.enabled(context, pack.id))
        }
        assertEquals(OrionPythonProfile.DEVELOPER, OrionOwnerDialSettings.read(context).pythonProfile)
        assertEquals(4, OrionAutonomySettings.read(context).maxRetries)
        assertEquals(100, AgentPolicy.maxSteps(context))
        assertEquals(100, AgentPolicy.maxCalls(context))
        assertEquals(30, AgentPolicy.maxMinutes(context))
    }

}
