package com.phoneai.app

import android.content.Context
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
}
