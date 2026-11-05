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
            "http_get", "http_post", "tool"
        )
        assertEquals(expected, OrionPythonPhoneBridge.registeredMethods)
    }

    @Test fun theLaunchCommandFitsTheRuntimePolicyLimit() {
        // Regression: a 4,000-character cap rejected every python_exec run ("Linux command rejected by runtime policy").
        listOf(false, true).forEach { full ->
            val command = "ulimit -c 0; ulimit -t 280; ulimit -f 65536; ulimit -n 256; ulimit -u 128; cd /workspace && exec python3 -I -c '" + ("import base64;exec(base64.urlsafe_b64decode(\"" + java.util.Base64.getUrlEncoder().encodeToString(OrionPythonPhoneBridge.bootstrapSource(full).toByteArray(Charsets.UTF_8)) + "\"))") + "'"
            assertTrue("command is " + command.length, command.length <= LinuxRuntimePolicy.MAX_COMMAND)
            assertTrue(LinuxRuntimePolicy.prootArgs("/bin/proot", "/rootfs", command, "/ws") != null)
        }
    }

    @Test fun fullProfileOmitsTheAuditHookButKeepsTheBridge() {
        val source = OrionPythonPhoneBridge.bootstrapSource(full = true)
        assertFalse(source.contains("sys.addaudithook(_orion_audit)"))
        assertTrue(source.contains("phone = _OrionPhone()"))
        assertTrue(source.contains("ORION_PHONE_API_TOKEN"))
    }

    @Test fun bootstrapUsesAuthenticatedLoopbackAndInjectsPhoneFacade() {
        val source = OrionPythonPhoneBridge.bootstrapSource()
        assertTrue(source.contains("http://127.0.0.1:"))
        assertTrue(source.contains("ORION_PHONE_API_TOKEN"))
        assertTrue(source.contains("phone = _OrionPhone()"))
        assertTrue(source.contains("sys.addaudithook(_orion_audit)"))
        assertTrue(source.contains("socket.getaddrinfo"))
        assertTrue(source.contains("subprocess.Popen"))
        assertTrue(source.contains("sys.path.insert(0, \"/workspace\")"))
        assertTrue(source.contains("/workspace/orion_main.py"))
        assertFalse(source.contains("0.0.0.0"))
        assertFalse(source.contains("getattr(api"))
    }
}
