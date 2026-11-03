package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionPythonPhoneBridgeTest {
    @Test fun phoneBridgeExposesOnlyTheRegisteredAndroidFacade() {
        val expected = setOf(
            "battery", "location", "notifications", "contacts",
            "accessibility_open_settings", "accessibility_status", "accessibility_screen",
            "ui_tap_text", "ui_tap", "ui_swipe", "fs_list", "fs_read", "fs_write", "shell",
            "ws_connect", "ws_connect_with_headers", "ws_send", "ws_send_binary", "ws_receive", "ws_close",
            "http_get", "http_post"
        )
        assertEquals(expected, OrionPythonPhoneBridge.registeredMethods)
    }

    @Test fun bootstrapUsesAuthenticatedLoopbackAndInjectsPhoneFacade() {
        val source = OrionPythonPhoneBridge.bootstrapSource()
        assertTrue(source.contains("http://127.0.0.1:"))
        assertTrue(source.contains("ORION_PHONE_API_TOKEN"))
        assertTrue(source.contains("phone = _OrionPhone()"))
        assertTrue(source.contains("/workspace/orion_main.py"))
        assertFalse(source.contains("0.0.0.0"))
        assertFalse(source.contains("getattr(api"))
    }
}
