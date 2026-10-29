package com.phoneai.app

/**
 * Whether Orion's keyboard is enabled / selected. Android 14+ can deny or truncate the underlying lookups
 * (SecurityException, null lists, incomplete component names), so any failure means "unknown = not enabled",
 * never a crash on the Settings screen.
 */
internal object KeyboardStatus {
    fun enabled(packageName: String, enabledPackages: () -> List<String?>?): Boolean = try {
        enabledPackages()?.any { it?.equals(packageName, ignoreCase = true) == true } == true
    } catch (_: Exception) { false }

    fun selected(packageName: String, defaultIme: () -> String?): Boolean = try {
        defaultIme()?.substringBefore('/')?.equals(packageName, ignoreCase = true) == true
    } catch (_: Exception) { false }
}
