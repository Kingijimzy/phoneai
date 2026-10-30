package com.phoneai.app

import android.content.Context

/** Owner-configurable dials. These preferences tune Orion; they never remove mandatory trust boundaries. */
internal enum class OrionPythonProfile { GUARDED, DEVELOPER }

internal data class OrionOwnerDials(
    val autoActivateVerifiedExtensions: Boolean = false,
    val pythonProfile: OrionPythonProfile = OrionPythonProfile.GUARDED,
    val recoveryMaxAttempts: Int = 3,
    val recoveryBackoffSeconds: Int = 30,
    val watcherEnabled: Boolean = false
)

internal object OrionOwnerDialSettings {
    private const val FILE = "orion_owner_dials"

    fun read(context: Context): OrionOwnerDials {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val profile = runCatching {
            OrionPythonProfile.valueOf(p.getString("python_profile", "GUARDED") ?: "GUARDED")
        }.getOrDefault(OrionPythonProfile.GUARDED)
        return OrionOwnerDials(
            autoActivateVerifiedExtensions = p.getBoolean("auto_activate_verified_extensions", false),
            pythonProfile = profile,
            recoveryMaxAttempts = p.getInt("recovery_max_attempts", 3).coerceIn(0, 4),
            recoveryBackoffSeconds = p.getInt("recovery_backoff_seconds", 30).coerceIn(15, 300),
            watcherEnabled = p.getBoolean("watcher_enabled", false)
        )
    }

    fun write(context: Context, value: OrionOwnerDials) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("auto_activate_verified_extensions", value.autoActivateVerifiedExtensions)
            .putString("python_profile", value.pythonProfile.name)
            .putInt("recovery_max_attempts", value.recoveryMaxAttempts.coerceIn(0, 4))
            .putInt("recovery_backoff_seconds", value.recoveryBackoffSeconds.coerceIn(15, 300))
            .putBoolean("watcher_enabled", value.watcherEnabled)
            .apply()
    }

    /** A profile can relax ergonomics and size limits, never the sandbox or approval requirement. */
    fun pythonMaxCodeChars(context: Context): Int = when (read(context).pythonProfile) {
        OrionPythonProfile.GUARDED -> 8_000
        OrionPythonProfile.DEVELOPER -> 16_000
    }
}
