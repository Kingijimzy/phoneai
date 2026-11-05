package com.phoneai.app

import android.content.Context

/** Owner-configurable dials. These preferences tune Orion; they never remove mandatory trust boundaries. */
internal enum class OrionPythonProfile { GUARDED, DEVELOPER, FULL }

internal data class OrionOwnerDials(
    val autoActivateVerifiedExtensions: Boolean = true,
    val pythonProfile: OrionPythonProfile = OrionPythonProfile.DEVELOPER,
    val recoveryMaxAttempts: Int = 3,
    val recoveryBackoffSeconds: Int = 30
)

internal object OrionOwnerDialSettings {
    private const val FILE = "orion_owner_dials"
    private const val AUTO_ACTIVATION_MIGRATION = "verified_extension_auto_activation_migrated_v1"

    fun read(context: Context): OrionOwnerDials {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // The owner requested automatic activation after exact-hash sandbox tests. Apply once
        // to legacy installs; subsequent explicit owner changes are preserved.
        if (!p.getBoolean(AUTO_ACTIVATION_MIGRATION, false)) {
            p.edit()
                .putBoolean("auto_activate_verified_extensions", true)
                .putBoolean(AUTO_ACTIVATION_MIGRATION, true)
                .apply()
        }
        val profile = runCatching {
            OrionPythonProfile.valueOf(p.getString("python_profile", "DEVELOPER") ?: "DEVELOPER")
        }.getOrDefault(OrionPythonProfile.GUARDED)
        return OrionOwnerDials(
            autoActivateVerifiedExtensions = p.getBoolean("auto_activate_verified_extensions", true),
            pythonProfile = profile,
            recoveryMaxAttempts = p.getInt("recovery_max_attempts", 3).coerceIn(0, 4),
            recoveryBackoffSeconds = p.getInt("recovery_backoff_seconds", 30).coerceIn(15, 300)
        )
    }

    fun write(context: Context, value: OrionOwnerDials) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("auto_activate_verified_extensions", value.autoActivateVerifiedExtensions)
            .putBoolean(AUTO_ACTIVATION_MIGRATION, true)
            .putString("python_profile", value.pythonProfile.name)
            .putInt("recovery_max_attempts", value.recoveryMaxAttempts.coerceIn(0, 4))
            .putInt("recovery_backoff_seconds", value.recoveryBackoffSeconds.coerceIn(15, 300))
            .apply()
    }

    /** GUARDED/DEVELOPER relax size only. FULL (owner opt-in, set in the credential-gated Owner Control Centre) also lifts the in-guest socket/subprocess block; per-run approval and the guest isolation stay. */
    fun pythonMaxCodeChars(context: Context): Int = when (read(context).pythonProfile) {
        OrionPythonProfile.GUARDED -> 8_000
        OrionPythonProfile.DEVELOPER -> 16_000
        OrionPythonProfile.FULL -> PythonExecutionPolicy.FULL_MAX_CODE_CHARS
    }
}
