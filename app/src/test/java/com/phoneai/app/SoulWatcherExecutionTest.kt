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
class SoulWatcherExecutionTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("orion_soul_state_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("orion_soul_lifecycle_v1", Context.MODE_PRIVATE).edit().clear().commit()
        AgentPolicy.setEnabled(context, "orion_core", false)
        AgentPolicy.setEnabled(context, "proactive", false)
    }

    @Test fun disabledCoreDoesNotReportMonitoringAndStillSavesStandingPurpose() {
        val result = SoulEngineWatcher.run(context)
        val snapshot = SoulEngineState.snapshot(context)

        assertEquals("paused", result.status)
        assertEquals(OrionSoulLifecycle.State.STOPPED, OrionSoulLifecycle.state(context))
        assertEquals(OrionInnerLife.PRIME_DIRECTIVE, snapshot.purpose)
        assertTrue(snapshot.watcherAt > 0L)
        assertEquals("paused", snapshot.watcherStatus)
    }

    @Test fun disabledProactivePackDoesNotReportActiveMonitoring() {
        AgentPolicy.setEnabled(context, "orion_core", true)
        AgentPolicy.setEnabled(context, "proactive", false)

        SoulEngineWatcher.run(context)

        assertEquals(OrionSoulLifecycle.State.PAUSED, OrionSoulLifecycle.state(context))
        assertEquals("proactive_pack_disabled", OrionSoulLifecycle.lastAction(context))
    }
}
