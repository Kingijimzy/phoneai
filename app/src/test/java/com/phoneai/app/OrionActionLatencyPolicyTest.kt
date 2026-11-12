package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OrionActionLatencyPolicyTest {
    @Test fun usesShortHandoffDelaysAndBoundsExplicitOverrides() {
        assertEquals(450L, OrionActionLatencyPolicy.LAUNCH_SETTLE_MS)
        assertEquals(180L, OrionActionLatencyPolicy.CLICK_SETTLE_MS)
        assertEquals(250L, OrionActionLatencyPolicy.WAIT_POLL_MS)
        assertEquals(0L, OrionActionLatencyPolicy.launchDelay(-10L))
        assertEquals(800L, OrionActionLatencyPolicy.launchDelay(800L))
        assertEquals(2_000L, OrionActionLatencyPolicy.launchDelay(10_000L))
    }
}
