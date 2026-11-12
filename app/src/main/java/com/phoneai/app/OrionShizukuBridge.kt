package com.phoneai.app

import android.app.Activity
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Optional Shizuku/Sui capability probe.
 *
 * This deliberately does not equate an active binder with root, and does not promise
 * invisible third-party app control. The shell UID has a restricted ADB permission set.
 * Privileged operations must be implemented as narrowly-scoped APIs and checked per action.
 */
internal object OrionShizukuBridge {
    private const val REQUEST_CODE = 0x4f52

    data class State(
        val installedAndRunning: Boolean,
        val permissionGranted: Boolean,
        val uid: Int?,
        val detail: String
    ) {
        val ready: Boolean get() = installedAndRunning && permissionGranted
    }

    fun state(): State {
        val alive = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!alive) {
            return State(
                installedAndRunning = false,
                permissionGranted = false,
                uid = null,
                detail = "Shizuku/Sui is not running. Install Shizuku and start it through Wireless debugging after reboot, or use Sui on a rooted device."
            )
        }
        val granted = try {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
        val uid = try { Shizuku.getUid() } catch (_: Throwable) { null }
        val identity = when (uid) {
            0 -> "root"
            2000 -> "ADB shell"
            null -> "unknown"
            else -> "uid $uid"
        }
        val detail = when {
            !granted -> "Shizuku is running as $identity, but Orion has not been granted Shizuku permission."
            uid == 2000 -> "Shizuku is ready with ADB shell identity. ADB permissions are limited and vary by Android version."
            uid == 0 -> "Shizuku is ready with root identity. Root operations remain subject to Orion's action policy."
            else -> "Shizuku binder is active with $identity; verify each operation's permission before use."
        }
        return State(true, granted, uid, detail)
    }

    /** Permission requests must originate from an explicit user-facing Activity. */
    fun requestPermission(activity: Activity): Boolean {
        val current = state()
        if (!current.installedAndRunning || current.permissionGranted) return current.permissionGranted
        return try {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun statusText(): String {
        val current = state()
        return buildString {
            appendLine("Shizuku/Sui: " + if (current.installedAndRunning) "running" else "not running")
            appendLine("Orion permission: " + if (current.permissionGranted) "granted" else "not granted")
            appendLine("Execution identity: " + (current.uid?.let { if (it == 0) "root (UID 0)" else if (it == 2000) "ADB shell (UID 2000)" else "UID $it" } ?: "unavailable"))
            append(current.detail)
        }
    }
}
