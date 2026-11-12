package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceOperationRoutePolicyTest {
    @Test fun nativeBackendWinsWhenAvailable() {
        assertEquals(DeviceBackend.NATIVE_ANDROID,
            DeviceOperationRoutePolicy.choose(DeviceOperation.SET_BRIGHTNESS, true, true).backend)
    }
    @Test fun shizukuHandlesSettingWritesWhenNativeAccessIsMissing() {
        assertEquals(DeviceBackend.SHIZUKU_USER_SERVICE,
            DeviceOperationRoutePolicy.choose(DeviceOperation.SET_SCREEN_TIMEOUT, false, true).backend)
    }
    @Test fun noShizukuMeansSettingsHandoffForWriteOperations() {
        assertEquals(DeviceBackend.ACCESSIBILITY_HANDOFF,
            DeviceOperationRoutePolicy.choose(DeviceOperation.SET_BRIGHTNESS, false, false).backend)
    }
    @Test fun packageInventoryUsesShizukuOnlyWhenShellIdentityIsReady() {
        assertEquals(DeviceBackend.SHIZUKU_USER_SERVICE,
            DeviceOperationRoutePolicy.choose(DeviceOperation.LIST_USER_PACKAGES, false, true).backend)
        assertEquals(DeviceBackend.UNAVAILABLE,
            DeviceOperationRoutePolicy.choose(DeviceOperation.LIST_USER_PACKAGES, false, false).backend)
    }
    @Test fun ordinaryNativeReadsNeverRouteToShizukuOrAccessibility() {
        assertEquals(DeviceBackend.NATIVE_ANDROID,
            DeviceOperationRoutePolicy.choose(DeviceOperation.READ_BATTERY, true, true).backend)
    }
    @Test fun unsupportedReadDoesNotFallBackToAccessibility() {
        assertEquals(DeviceBackend.UNAVAILABLE,
            DeviceOperationRoutePolicy.choose(DeviceOperation.READ_BATTERY, false, false).backend)
    }
}