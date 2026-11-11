package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrreversibleActionConfirmationPolicyTest {
    @Test fun onlyRestartShutdownAndWifiChangesAreClassifiedAsComputerSettingActions() {
        assertEquals("restart", IrreversibleActionConfirmationPolicy.computerSettingAction(
            "computer_settings", JSONObject().put("action", "reboot")
        ))
        assertEquals("shutdown", IrreversibleActionConfirmationPolicy.computerSettingAction(
            "computer_settings", JSONObject().put("operation", "power_off")
        ))
        assertEquals("toggle Wi-Fi", IrreversibleActionConfirmationPolicy.computerSettingAction(
            "computer_settings", JSONObject().put("setting", "disable_wifi")
        ))
        assertNull(IrreversibleActionConfirmationPolicy.computerSettingAction(
            "computer_settings", JSONObject().put("action", "open_settings")
        ))
        assertNull(IrreversibleActionConfirmationPolicy.computerSettingAction(
            "open_app", JSONObject().put("name", "Settings")
        ))
    }

    @Test fun nestedMcpComputerSettingActionsUseTheSameConfirmationRule() {
        val args = JSONObject()
            .put("name", "computer_settings")
            .put("arguments", JSONObject().put("action", "toggle_wifi"))
        assertEquals("toggle Wi-Fi", IrreversibleActionConfirmationPolicy.computerSettingAction("mcp_call", args))
    }

    @Test fun confirmationSurfaceMatchesOnlyTheThreeIrreversibleSettings() {
        assertTrue(IrreversibleActionConfirmationPolicy.requiresConfirmation("Confirm computer_settings action: restart?"))
        assertTrue(IrreversibleActionConfirmationPolicy.requiresConfirmation("Confirm computer_settings action: shutdown?"))
        assertTrue(IrreversibleActionConfirmationPolicy.requiresConfirmation("Confirm computer_settings action: toggle Wi-Fi?"))
        assertFalse(IrreversibleActionConfirmationPolicy.requiresConfirmation("Confirm computer_settings action: open settings?"))
        assertFalse(IrreversibleActionConfirmationPolicy.requiresConfirmation("Confirm browser action: restart?"))
    }
}
