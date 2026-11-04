package com.phoneai.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionAutonomySettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun ownerNotificationDialsPersistIndependently() {
        context.getSharedPreferences("phoneai", Context.MODE_PRIVATE).edit().clear().commit()
        val defaults = OrionAutonomySettings.read(context)
        assertTrue(defaults.notifyOnCompletion)
        assertTrue(defaults.notifyOnFailure)
        assertTrue(defaults.notifyOnApproval)

        OrionAutonomySettings.write(
            context,
            defaults.copy(
                notifyOnCompletion = false,
                notifyOnFailure = true,
                notifyOnApproval = false
            )
        )
        val saved = OrionAutonomySettings.read(context)
        assertFalse(saved.notifyOnCompletion)
        assertTrue(saved.notifyOnFailure)
        assertFalse(saved.notifyOnApproval)
    }

    @Test fun ownerExecutionLimitsPersistWithinTheirClampedRange() {
        context.getSharedPreferences("phoneai", Context.MODE_PRIVATE).edit().clear().commit()
        OrionAutonomySettings.write(
            context,
            OrionAutonomyConfig(
                mode = OrionMode.BUILDER,
                maxToolSteps = 1000,
                maxAiRequests = 0,
                maxRetries = -1
            )
        )
        val saved = OrionAutonomySettings.read(context)
        assertEquals(OrionMode.BUILDER, saved.mode)
        assertEquals(40, saved.maxToolSteps)
        assertEquals(1, saved.maxAiRequests)
        assertEquals(0, saved.maxRetries)
    }
}
