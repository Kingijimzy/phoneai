package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/** User-owned execution controls. Limits are enforced by the command/agent loop, not prompt-only wishes. */
internal enum class OrionMode { TALK, ASSIST, AUTOPILOT, BUILDER }

internal data class OrionAutonomyConfig(
    val mode: OrionMode = OrionMode.ASSIST,
    val maxToolSteps: Int = 8,
    val maxAiRequests: Int = 15,
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
        OrionMode.TALK -> "Conversation and read-only tools; changes are blocked."
        OrionMode.ASSIST -> "Help plan and act with the current per-tool policy."
        OrionMode.AUTOPILOT -> "Execute permitted multi-step goals; existing confirmation and safety gates still apply."
        OrionMode.BUILDER -> "Create and test disabled drafts; activation follows the current approval policy."
    }
}

internal object OrionAutonomySettings {
    private const val FILE = "phoneai"
    fun read(c: Context): OrionAutonomyConfig {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val mode = runCatching { OrionMode.valueOf(p.getString("orion_mode", "ASSIST") ?: "ASSIST") }.getOrDefault(OrionMode.ASSIST)
        return OrionAutonomyPolicy.clamp(OrionAutonomyConfig(
            mode = mode,
            maxToolSteps = p.getInt("orion_max_tool_steps", 8),
            maxAiRequests = p.getInt("orion_max_ai_requests", 15),
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
            .putInt("orion_max_tool_steps", v.maxToolSteps)
            .putInt("orion_max_ai_requests", v.maxAiRequests)
            .putInt("orion_max_retries", v.maxRetries)
            .putBoolean("orion_notify_completion", v.notifyOnCompletion)
            .putBoolean("orion_notify_failure", v.notifyOnFailure)
            .putBoolean("orion_notify_approval", v.notifyOnApproval)
            .apply()
    }
}
