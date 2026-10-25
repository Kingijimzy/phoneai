package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemIntentPolicyTest {
    @Test fun allowsEveryDocumentedDeterministicPhoneRoute() {
        assertEquals(
            setOf(
                "settings", "wifi_settings", "bluetooth_settings", "display_settings",
                "app_settings", "location_settings", "accessibility_settings",
                "notification_settings", "dial", "maps_search", "camera", "calendar",
                "clock", "sms", "alarm", "timer", "share", "open_url"
            ),
            SystemIntentPolicy.actions()
        )
        SystemIntentPolicy.actions().forEach { action ->
            assertTrue("Expected documented route to be allowed: $action", SystemIntentPolicy.isAllowed(action))
        }
    }

    @Test fun registryContractDocumentsEveryAllowedRoute() {
        val contract = ToolRegistry.find("system_intent")!!.promptLine
        SystemIntentPolicy.actions().forEach { action ->
            assertTrue("Tool contract is missing route $action", contract.contains(action))
        }
    }

    @Test fun normalizesHumanReadableAliases() {
        assertEquals("wifi_settings", SystemIntentPolicy.normalize(" Wi-Fi Settings "))
        assertEquals("notification_settings", SystemIntentPolicy.normalize("Notification Settings"))
        assertTrue(SystemIntentPolicy.isAllowed("Wi-Fi Settings"))
        assertTrue(SystemIntentPolicy.isAllowed("open-url"))
    }

    @Test fun rejectsUnknownOrArbitraryAndroidActions() {
        assertFalse(SystemIntentPolicy.isAllowed(""))
        assertFalse(SystemIntentPolicy.isAllowed("android.intent.action.MAIN"))
        assertFalse(SystemIntentPolicy.isAllowed("com.example.PRIVATE_ACTIVITY"))
        assertFalse(SystemIntentPolicy.isAllowed("intent://anything"))
        assertFalse(SystemIntentPolicy.isAllowed("start arbitrary component"))
    }
}
