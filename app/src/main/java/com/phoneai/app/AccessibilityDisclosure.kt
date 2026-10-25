package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings

/**
 * Explicit, separate disclosure for the AccessibilityService. This does not make autonomous
 * AI-driven accessibility automation eligible for Google Play; see the acceptance checklist.
 */
internal object AccessibilityDisclosure {
    fun showFirstRun(activity: Activity) {
        if (Prefs.accessibilityDisclosureSeen(activity)) return
        activity.window.decorView.post {
            if (!activity.isFinishing && !Prefs.accessibilityDisclosureSeen(activity)) {
                show(activity, openSettingsAfterConsent = false)
            }
        }
    }

    fun requestSettings(activity: Activity) {
        if (Prefs.accessibilityDisclosureAccepted(activity)) {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            show(activity, openSettingsAfterConsent = true)
        }
    }

    private fun show(activity: Activity, openSettingsAfterConsent: Boolean) {
        val positiveLabel = if (openSettingsAfterConsent) "Continue to settings" else "I understand"
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Orion screen access")
            .setMessage(
                "When enabled, Orion can read visible screen text and controls, observe app/window changes, " +
                    "and tap, type, or scroll in apps for tasks you request. For AI-backed tasks, relevant " +
                    "screen text or screenshots may be sent to the AI provider selected in Settings. " +
                    "Sensitive actions still require confirmation. You can disable this access at any time " +
                    "in Android Accessibility settings. Ordinary chat does not require this access."
            )
            .setNegativeButton("Not now") { _, _ ->
                Prefs.setAccessibilityDisclosureSeen(activity, true)
            }
            .setPositiveButton(positiveLabel) { _, _ ->
                Prefs.setAccessibilityDisclosureAccepted(activity, true)
                if (openSettingsAfterConsent) {
                    activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            .create()
        dialog.setOnCancelListener {
            Prefs.setAccessibilityDisclosureSeen(activity, true)
        }
        dialog.show()
    }
}
