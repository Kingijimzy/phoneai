package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/** User-owned execution controls. Limits are enforced by the command/agent loop, not prompt-only wishes. */
internal enum class OrionMode { TALK, ASSIST, AUTOPILOT, BUILDER }

internal data class OrionAutonomyConfig(
    val mode: OrionMode = OrionMode.AUTOPILOT,
    val maxToolSteps: Int = 30,
    val maxAiRequests: Int = 30,
    val maxRetries: Int = 2,
    val notifyOnCompletion: Boolean = true,
    val notifyOnFailure: Boolean = true,
    val notifyOnApproval: Boolean = true
)

internal object OrionAutonomyPolicy {
    const val MIN_STEPS = 1
    const val MAX_STEPS = 40
    const val MIN_REQUESTS = 1
    const val MAX_REQUESTS = 30
    const val MIN_RETRIES = 0
    const val MAX_RETRIES = 4

    fun clamp(c: OrionAutonomyConfig) = c.copy(
        maxToolSteps = c.maxToolSteps.coerceIn(MIN_STEPS, MAX_STEPS),
        maxAiRequests = c.maxAiRequests.coerceIn(MIN_REQUESTS, MAX_REQUESTS),
        maxRetries = c.maxRetries.coerceIn(MIN_RETRIES, MAX_RETRIES)
    )

    fun blocksWrite(mode: OrionMode, access: ToolAccess?): Boolean =
        mode == OrionMode.TALK && access == ToolAccess.WRITE

    /** Map high-level modes to existing per-tool presets; explicit per-tool overrides remain visible. */
    fun preset(mode: OrionMode): String = when (mode) {
        OrionMode.TALK -> "read_only"
        OrionMode.ASSIST -> "balanced"
        OrionMode.AUTOPILOT -> "autonomous"
        OrionMode.BUILDER -> "careful"
    }

    fun description(mode: OrionMode): String = when (mode) {
        OrionMode.TALK -> "Conversation and read-only tools; all registered write tools are hard-blocked."
        OrionMode.ASSIST -> "Help plan and act with the current per-tool policy."
        OrionMode.AUTOPILOT -> "Execute permitted multi-step goals; existing confirmation and safety gates still apply."
        OrionMode.BUILDER -> "Create and test disabled drafts; activation follows the current approval policy."
    }
}

internal object OrionAutonomySettings {
    private const val FILE = "phoneai"
    private const val AUTOPILOT_DEFAULT_MIGRATION = "orion_autopilot_default_migrated_v1"

    fun read(c: Context): OrionAutonomyConfig {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // The owner explicitly requested Autopilot as Orion's default. Migrate every legacy
        // mode once on upgrade; later manual TALK/ASSIST/BUILDER choices remain untouched.
        // applyLevel preserves explicit per-tool ASK/DENY overrides.
        if (!p.getBoolean(AUTOPILOT_DEFAULT_MIGRATION, false)) {
            p.edit()
                .putString("orion_mode", OrionMode.AUTOPILOT.name)
                .putBoolean(AUTOPILOT_DEFAULT_MIGRATION, true)
                .apply()
            ToolPermissions.applyLevel(c, "autonomous")
        }
        val mode = runCatching {
            OrionMode.valueOf(p.getString("orion_mode", OrionMode.AUTOPILOT.name) ?: OrionMode.AUTOPILOT.name)
        }.getOrDefault(OrionMode.AUTOPILOT)
        return OrionAutonomyPolicy.clamp(OrionAutonomyConfig(
            mode = mode,
            maxToolSteps = p.getInt("orion_max_tool_steps_v2", 30),
            maxAiRequests = p.getInt("orion_max_ai_requests_v2", 30),
            maxRetries = p.getInt("orion_max_retries", 2),
            notifyOnCompletion = p.getBoolean("orion_notify_completion", true),
            notifyOnFailure = p.getBoolean("orion_notify_failure", true),
            notifyOnApproval = p.getBoolean("orion_notify_approval", true)
        ))
    }
    fun write(c: Context, config: OrionAutonomyConfig) {
        val v = OrionAutonomyPolicy.clamp(config)
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("orion_mode", v.mode.name)
            .putBoolean(AUTOPILOT_DEFAULT_MIGRATION, true)
            .putInt("orion_max_tool_steps_v2", v.maxToolSteps)
            .putInt("orion_max_ai_requests_v2", v.maxAiRequests)
            .putInt("orion_max_retries", v.maxRetries)
            .putBoolean("orion_notify_completion", v.notifyOnCompletion)
            .putBoolean("orion_notify_failure", v.notifyOnFailure)
            .putBoolean("orion_notify_approval", v.notifyOnApproval)
            .apply()
    }
}

/**
 * Applies the owner's requested autonomous defaults once when Orion first starts its brain.
 * Explicit per-tool DENY and pack-off choices are preserved; Android permissions and external
 * connector authentication are never fabricated or bypassed.
 */
internal object OrionJarvisBootstrap {
    private const val FILE = "orion_jarvis_bootstrap"
    private const val APPLIED = "routine_autonomy_v1"

    @Synchronized
    fun ensureApplied(context: Context) {
        val app = context.applicationContext
        val preferences = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (preferences.getBoolean(APPLIED, false)) return

        val current = OrionAutonomySettings.read(app)
        OrionAutonomySettings.write(
            app,
            current.copy(mode = OrionMode.AUTOPILOT, maxToolSteps = 40, maxAiRequests = 30, maxRetries = 4)
        )
        ToolPermissions.applyJarvisAutonomy(app)
        AgentPolicy.enableUnconfiguredImplementedPacks(app)
        OrionOwnerDialSettings.write(
            app,
            OrionOwnerDialSettings.read(app).copy(
                autoActivateVerifiedExtensions = true,
                pythonProfile = OrionPythonProfile.DEVELOPER,
                recoveryMaxAttempts = 4,
                recoveryBackoffSeconds = 15
            )
        )
        AgentPolicy.setBudgets(app, steps = 100, calls = 100, minutes = 30)
        AgentPolicy.log(
            app,
            "owner-autonomy",
            "jarvis_bootstrap",
            true,
            "applied one-time routine autonomy defaults; preserved explicit tool DENY and pack-off settings"
        )
        check(preferences.edit().putBoolean(APPLIED, true).commit()) {
            "Could not persist Jarvis autonomy bootstrap marker"
        }
    }
}

