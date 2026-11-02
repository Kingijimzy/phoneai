package com.phoneai.app

import android.content.Context
import org.junit.After
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
class OrionContextRetentionIntegrationTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("orion_sensor_consent_v1", Context.MODE_PRIVATE).edit().clear().commit()
        OrionSensorContextStore.clear(context)
    }

    @After fun tearDown() {
        context.getSharedPreferences("orion_sensor_consent_v1", Context.MODE_PRIVATE).edit().clear().commit()
        OrionSensorContextStore.clear(context)
    }

    @Test fun revokingMotionGrantImmediatelyBlocksSamplingAndClearsCachedSummary() {
        OrionSensorConsent.grant(context, OrionSensorScope.MOTION, 60_000L)
        assertTrue(OrionSensorContextStore.recordMotion(context, "coarse movement", System.currentTimeMillis()))
        assertTrue(OrionSensorContextStore.summary(context).contains("coarse movement"))

        OrionSensorConsent.revoke(context, OrionSensorScope.MOTION)

        assertFalse(OrionSensorConsent.read(context, OrionSensorScope.MOTION).active())
        assertFalse(OrionSensorContextStore.recordMotion(context, "must not persist"))
        assertEquals("", OrionSensorContextStore.summary(context))
    }

    @Test fun expiredGrantIsRevokedAndCannotRefreshItsContext() {
        val grant = OrionSensorConsent.grant(context, OrionSensorScope.MOTION, 100L)
        assertTrue(grant.expiresAt > grant.grantedAt)
        assertFalse(OrionSensorConsent.read(context, OrionSensorScope.MOTION, grant.expiresAt).active())
        assertFalse(OrionSensorContextStore.recordMotion(context, "expired", grant.expiresAt))
        assertEquals("", OrionSensorContextStore.summary(context, grant.expiresAt))
        assertFalse(context.getSharedPreferences("orion_sensor_consent_v1", Context.MODE_PRIVATE)
            .getBoolean("MOTION_enabled", true))
    }

    @Test fun eachAmbientSourceHasAnIndependentGrantAndRevocation() {
        OrionSensorScope.values().forEach { scope ->
            OrionSensorConsent.grant(context, scope, 60_000L)
        }
        assertEquals(OrionSensorScope.values().toSet(), OrionSensorConsent.activeScopes(context))

        OrionSensorConsent.revoke(context, OrionSensorScope.NOTIFICATIONS)

        assertFalse(OrionSensorConsent.read(context, OrionSensorScope.NOTIFICATIONS).active())
        assertTrue(OrionSensorConsent.activeScopes(context).contains(OrionSensorScope.MOTION))
        assertFalse(OrionSensorConsent.activeScopes(context).contains(OrionSensorScope.NOTIFICATIONS))
    }

    @Test fun expiredGrantCannotBeReactivatedByAStaleCachedValue() {
        val grant = OrionSensorConsent.grant(context, OrionSensorScope.LOCATION, 50L)
        val staleCopy = grant
        assertFalse(OrionSensorConsent.read(context, OrionSensorScope.LOCATION, staleCopy.expiresAt).active())
        assertFalse(OrionSensorConsent.read(context, OrionSensorScope.LOCATION, staleCopy.grantedAt).active())
    }
}
