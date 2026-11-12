package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.provider.Settings

/**
 * Opens Android's own Accessibility settings. Orion does not add a separate in-app consent gate;
 * Android's system-managed accessibility grant remains the access boundary.
 */
internal object AccessibilityDisclosure {
    fun showFirstRun(activity: Activity) {
        // Intentionally no first-run dialog. The user manages this grant in Android Settings.
    }

    fun requestSettings(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
