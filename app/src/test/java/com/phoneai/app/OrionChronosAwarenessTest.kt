package com.phoneai.app

import android.content.Context
import org.junit.After
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
class OrionChronosAwarenessTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        OrionChronosAwareness.clear(context)
        OrionSensorScope.values().forEach { OrionSensorConsent.revoke(context, it) }
    }

    @After fun tearDown() {
        OrionChronosAwareness.clear(context)
        OrionSensorScope.values().forEach { OrionSensorConsent.revoke(context, it) }
    }

    private fun grantChronos(background: Boolean = true) {
        OrionSensorConsent.grant(context, OrionSensorScope.APP_USAGE, 4 * 60 * 60 * 1000L)
        OrionSensorConsent.grant(
            context, OrionSensorScope.CHRONOS_AWARENESS, 4 * 60 * 60 * 1000L,
            foregroundOnly = !background
        )
    }

    @Test fun noConsentMeansNoAmbientContextIsCollected() {
        assertFalse(OrionChronosAwareness.onWindowChanged(context, "com.whatsapp"))
        assertTrue(OrionChronosAwareness.summary(context).isBlank())
    }

    @Test fun backgroundChronosConsentStoresOnlyCoarseCategory() {
        grantChronos()
        assertTrue(OrionChronosAwareness.onWindowChanged(context, "com.whatsapp"))
        val summary = OrionChronosAwareness.summary(context)
        assertTrue(summary.contains("category=communication"))
        assertFalse(summary.contains("whatsapp"))
        assertFalse(summary.contains("com."))
    }

    @Test fun sensitiveFinancialAndCredentialAppsAreNeverRecorded() {
        grantChronos()
        assertFalse(OrionChronosAwareness.onWindowChanged(context, "com.bybit.app"))
        assertTrue(OrionChronosAwareness.summary(context).isBlank())
        assertFalse(OrionChronosAwareness.onWindowChanged(context, "com.example.authenticator"))
        assertTrue(OrionChronosAwareness.summary(context).isBlank())
    }

    @Test fun foregroundOnlyChronosGrantCannotCollectBackgroundWindowEvents() {
        grantChronos(background = false)
        assertFalse(OrionChronosAwareness.onWindowChanged(context, "com.whatsapp"))
        assertTrue(OrionChronosAwareness.summary(context).isBlank())
    }

    @Test fun revokingEitherRequiredGrantClearsTheStoredContext() {
        grantChronos()
        assertTrue(OrionChronosAwareness.onWindowChanged(context, "com.chrome.browser"))
        OrionSensorConsent.revoke(context, OrionSensorScope.APP_USAGE)
        assertTrue(OrionChronosAwareness.summary(context).isBlank())
    }

    @Test fun staleContextExpiresAfterTwoHours() {
        grantChronos()
        val now = System.currentTimeMillis()
        assertTrue(OrionChronosAwareness.onWindowChanged(context, "com.chrome.browser", now))
        assertTrue(OrionChronosAwareness.summary(context, now + 2 * 60 * 60 * 1000L + 1).isBlank())
    }
}
