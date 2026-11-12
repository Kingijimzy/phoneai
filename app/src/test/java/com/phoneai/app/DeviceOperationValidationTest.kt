package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceOperationValidationTest {
    @Test fun parsesOnlyKnownOperations() {
        assertEquals(DeviceOperation.SET_BRIGHTNESS, DeviceOperation.parse("set_brightness"))
        assertNull(DeviceOperation.parse("shell"))
        assertNull(DeviceOperation.parse("settings put system screen_brightness 10"))
    }
}