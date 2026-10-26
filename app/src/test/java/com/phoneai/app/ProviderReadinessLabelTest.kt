package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderReadinessLabelTest {
    @Test fun distinguishesMissingConnectionFromPaidOptInAndCooldown() {
        assertEquals("not connected", Nim.readinessLabel(false, false, false, 0))
        assertEquals(
            "connected, but blocked until paid/metered providers are enabled",
            Nim.readinessLabel(true, true, false, 0)
        )
        assertEquals(
            "configured, cooling down for 12 seconds",
            Nim.readinessLabel(true, false, false, 12)
        )
    }

    @Test fun doesNotClaimConfiguredProviderWasTested() {
        assertEquals(
            "configured; eligible to try (actual model and service availability are checked per request)",
            Nim.readinessLabel(true, false, false, 0)
        )
    }
}
