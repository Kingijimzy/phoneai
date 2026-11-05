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
class OrionOwnerDialSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun extensionAutoActivationDefaultsOnForOwnerRequestedAutopilot() {
        context.getSharedPreferences("orion_owner_dials", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(OrionOwnerDials().autoActivateVerifiedExtensions)
        assertTrue(OrionOwnerDialSettings.read(context).autoActivateVerifiedExtensions)
    }

    @Test fun legacyManualExtensionActivationMigratesToAutoOnceButLaterOwnerChoicePersists() {
        context.getSharedPreferences("orion_owner_dials", Context.MODE_PRIVATE).edit()
            .clear().putBoolean("auto_activate_verified_extensions", false).commit()
        assertTrue(OrionOwnerDialSettings.read(context).autoActivateVerifiedExtensions)
        OrionOwnerDialSettings.write(context, OrionOwnerDials(autoActivateVerifiedExtensions = false))
        assertFalse(OrionOwnerDialSettings.read(context).autoActivateVerifiedExtensions)
    }

    @Test fun firstRunUsesScopedDeveloperPythonRatherThanUnrestrictedFullProfile() {
        context.getSharedPreferences("orion_owner_dials", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(OrionPythonProfile.DEVELOPER, OrionOwnerDialSettings.read(context).pythonProfile)
    }

    @Test fun recoveryDialsAreClampedAndPersisted() {
        OrionOwnerDialSettings.write(context, OrionOwnerDials(recoveryMaxAttempts = 99, recoveryBackoffSeconds = 1))
        val saved = OrionOwnerDialSettings.read(context)
        assertEquals(4, saved.recoveryMaxAttempts)
        assertEquals(15, saved.recoveryBackoffSeconds)
        OrionOwnerDialSettings.write(context, saved.copy(recoveryMaxAttempts = 0, recoveryBackoffSeconds = 300))
        val updated = OrionOwnerDialSettings.read(context)
        assertEquals(0, updated.recoveryMaxAttempts)
        assertEquals(300, updated.recoveryBackoffSeconds)
    }

    @Test fun pythonProfileChangesOnlyTheCodeSizeBudget() {
        OrionOwnerDialSettings.write(context, OrionOwnerDials(pythonProfile = OrionPythonProfile.GUARDED))
        assertEquals(8_000, PythonExecutionPolicy.maxCodeChars(context))
        assertTrue(PythonExecutionPolicy.rejection("x".repeat(8_001)) != null)
        OrionOwnerDialSettings.write(context, OrionOwnerDials(pythonProfile = OrionPythonProfile.DEVELOPER))
        assertEquals(16_000, PythonExecutionPolicy.maxCodeChars(context))
        assertTrue(PythonExecutionPolicy.rejection("x".repeat(16_001), PythonExecutionPolicy.maxCodeChars(context)) != null)
    }
}
