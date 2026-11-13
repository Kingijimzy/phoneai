package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceOperationValidationTest {
    @Test fun parsesOnlyKnownOperations() {
        assertEquals(DeviceOperation.SET_BRIGHTNESS, DeviceOperation.parse("set_brightness"))
        assertEquals(DeviceOperation.SET_VOLUME, DeviceOperation.parse("SET_VOLUME"))
        assertEquals(DeviceOperation.SET_ALARM, DeviceOperation.parse("set_alarm"))
        assertEquals(DeviceOperation.WIFI_SETTINGS, DeviceOperation.parse("wifi_settings"))
        assertEquals(DeviceOperation.READ_STORAGE, DeviceOperation.parse("read_storage"))
        assertNull(DeviceOperation.parse("shell"))
        assertNull(DeviceOperation.parse("settings put system screen_brightness 10"))
        assertNull(DeviceOperation.parse("set_brightness; reboot"))
    }

    @Test fun operationVocabularyContainsNoRawCommandSurface() {
        DeviceOperation.values().forEach { operation ->
            assertNull(DeviceOperation.parse(operation.wireName + " arbitrary shell text"))
        }
    }
}
