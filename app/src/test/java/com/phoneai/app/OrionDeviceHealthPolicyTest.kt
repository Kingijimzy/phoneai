package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionDeviceHealthPolicyTest {
    @Test fun warnsForLowUnpluggedBatteryAndCriticalStorage() {
        val alerts = OrionDeviceHealthPolicy.alerts(12, false, 1_000_000_000L, 4)
        assertEquals(2, alerts.size)
    }
    @Test fun doesNotWarnForHealthyOrChargingBattery() {
        assertTrue(OrionDeviceHealthPolicy.alerts(12, true, 8_000_000_000L, 40).isEmpty())
        assertTrue(OrionDeviceHealthPolicy.alerts(80, false, 8_000_000_000L, 40).isEmpty())
    }
}