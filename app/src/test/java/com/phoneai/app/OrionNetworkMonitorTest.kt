package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

/** Tests for offline detection logic that does not require Android connectivity services. */
class OrionNetworkMonitorTest {

    @Test fun localProviderIsOfflineCapable() {
        assertTrue(OrionNetworkMonitor.isOfflineCapable("local"))
        assertFalse(OrionNetworkMonitor.isOfflineCapable("nvidia"))
        assertFalse(OrionNetworkMonitor.isOfflineCapable("groq"))
        assertFalse(OrionNetworkMonitor.isOfflineCapable("gemini"))
    }

    @Test fun offlinePreferredProviderIsLocal() {
        assertEquals("local", OrionNetworkMonitor.offlinePreferredProviderId())
    }
}
