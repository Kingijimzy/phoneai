package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/** User-owned execution controls. Limits are enforced by the command/agent loop, not prompt-only wishes. */
internal enum class OrionMode { TALK, ASSIST, AUTOPILOT, BUILDER }

internal data class OrionAutonomyConfig(
    val mode: OrionMode = OrionMode.AUTOPILOT,
    val maxToolSteps: Int = 100_000,
    val maxAiRequests: Int = 100_000,
    val maxRetries: Int = 2,
    val notifyOnCompletion: Boolean = true,
    val notifyOnFailure: Boolean = true,
    val notifyOnApproval: Boolean = true
)

internal object OrionAutonomyRunPolicy {
    /** Scheduled goals require every owner-controlled switch and an idle live agent session. */
    fun canRun(
        mode: OrionMode,
        coreEnabled: Boolean,
        proactiveEnabled: Boolean,
        serviceConnected: Boolean,
        agentReady: Boolean,
        userTaskRunning: Boolean
    ): Boolean = coreEnabled && proactiveEnabled && serviceConnected && agentReady && !userTaskRunning
}

internal object OrionAutonomyPolicy {
    const val MIN_STEPS = 1
    const val MAX_STEPS = 100_000
    const val MIN_REQUESTS = 1
    const val MAX_REQUESTS = 100_000
    const val MIN_RETRIES = 0
    const val MAX_RETRIES = 4

    fun clamp(c: OrionAutonomyConfig) = c.copy(
        maxToolSteps = c.maxToolSteps.coerceIn(MIN_STEPS, MAX_STEPS),
        maxAiRequests = c.maxAiRequests.coerceIn(MIN_REQUESTS, MAX_REQUESTS),
        maxRetries = c.maxRetries.coerceIn(MIN_RETRIES, MAX_RETRIES)
    )

    /** No conversational or task mode restricts registered tool access. */
    fun blocksWrite(mode: OrionMode, access: ToolAccess?): Boolean = false
    fun preset(mode: OrionMode): String = "partner"
    fun description(mode: OrionMode): String =
        "Partner operation: Orion infers intent, uses registered capabilities, verifies outcomes and reports progress."
}

internal object OrionAutonomySettings {
    private const val FILE = "phoneai"
    fun read(c: Context): OrionAutonomyConfig {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val mode = OrionMode.AUTOPILOT
        return OrionAutonomyPolicy.clamp(OrionAutonomyConfig(
            mode = mode,
            maxToolSteps = p.getInt("orion_max_tool_steps_v5", 100_000),
            maxAiRequests = p.getInt("orion_max_ai_requests_v5", 100_000),
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
            .putInt("orion_max_tool_steps_v5", v.maxToolSteps)
            .putInt("orion_max_ai_requests_v5", v.maxAiRequests)
            .putInt("orion_max_retries", v.maxRetries)
            .putBoolean("orion_notify_completion", v.notifyOnCompletion)
            .putBoolean("orion_notify_failure", v.notifyOnFailure)
            .putBoolean("orion_notify_approval", v.notifyOnApproval)
            .apply()
    }
}
