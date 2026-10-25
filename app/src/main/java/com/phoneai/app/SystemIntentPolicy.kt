package com.phoneai.app

/**
 * Single source of truth for deterministic Android routes exposed through system_intent.
 * This is a route allowlist, not permission to launch arbitrary Android components.
 */
internal object SystemIntentPolicy {
    private val allowedActions = setOf(
        "settings",
        "wifi_settings",
        "bluetooth_settings",
        "display_settings",
        "app_settings",
        "location_settings",
        "accessibility_settings",
        "notification_settings",
        "dial",
        "maps_search",
        "camera",
        "calendar",
        "clock",
        "sms",
        "alarm",
        "timer",
        "share",
        "open_url"
    )

    fun normalize(action: String): String =
        action.trim().lowercase().replace('-', '_').replace(' ', '_')

    fun isAllowed(action: String): Boolean = normalize(action) in allowedActions

    fun actions(): Set<String> = allowedActions
}
