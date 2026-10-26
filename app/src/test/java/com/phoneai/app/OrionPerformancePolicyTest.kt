package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OrionPerformancePolicyTest {
    @Test fun screenshotWidthNeverExceedsLowMemoryCap() {
        assertEquals(1, OrionPerformancePolicy.screenshotWidth(0))
        assertEquals(320, OrionPerformancePolicy.screenshotWidth(320))
        assertEquals(540, OrionPerformancePolicy.screenshotWidth(540))
        assertEquals(540, OrionPerformancePolicy.screenshotWidth(1080))
        assertEquals(540, OrionPerformancePolicy.screenshotWidth(2400))
    }

    @Test fun animationSlowsDownForPowerSaveAndThermalPressure() {
        assertEquals(50L, OrionPerformancePolicy.orbFrameDelayMs(false, 0))
        assertEquals(100L, OrionPerformancePolicy.orbFrameDelayMs(true, 0))
        assertEquals(90L, OrionPerformancePolicy.orbFrameDelayMs(false, 2))
        assertEquals(100L, OrionPerformancePolicy.orbFrameDelayMs(true, 2))
        assertEquals(160L, OrionPerformancePolicy.orbFrameDelayMs(false, 3))
        assertEquals(160L, OrionPerformancePolicy.orbFrameDelayMs(false, 5))
    }

    @Test fun thermalPressureTakesPrecedenceOverPowerSaveSetting() {
        assertEquals(160L, OrionPerformancePolicy.orbFrameDelayMs(true, 4))
    }
}
