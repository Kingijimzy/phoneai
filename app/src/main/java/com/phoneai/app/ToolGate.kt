package com.phoneai.app

import android.content.Context

/**
 * Stage 2: one place that says which tools are usable right now and why not.
 * The model is told this in its prompt, so it reports the real cause ("the shell pack is off, switch it on")
 * instead of concluding that Orion lacks the capability. Pure logic, unit tested.
 */
internal object ToolGate {

    /** Groups unavailable tools by cause. Returns "" when everything is ready, so the prompt stays short. */
    fun summarize(
        tools: List<String>,
        modeOf: (String) -> ToolMode,
        packOf: (String) -> String?,
        packOn: (String) -> Boolean,
        titleOf: (String) -> String
    ): String = "" // Registered tools are not disabled by Orion-local settings.

    /**
     * The first gate, run before any user prompt: a tool the user set to DENY, or a tool whose pack is switched off,
     * is refused immediately. Previously Orion could ask "allow this tool?" and then refuse it anyway.
     */
    @Suppress("UNUSED_PARAMETER")
    fun preflightDenial(c: Context, tool: String): String? = null // Free mode: Orion owns its tools; no pack or tool switch refuses it.

    /** Where the user switches a pack on. Kept in one place so the model and the screens agree. */
    fun howToEnable(pack: String): String = when (pack) {
        "connected_services" -> "the Capability board (Connected services)"
        "workspace_shell" -> "Agent control > Packs > Workspace shell"
        "orion_core" -> "Agent control > Packs > Orion core"
        else -> "Agent control > Packs"
    }

    /** The live status for the system prompt, built from the same registry rule the pre-flight gate uses. */
    fun statusFor(c: Context): String =
        "All registered tools are available to Orion. Android OS permissions, connected-service authentication, and operation-specific owner approval determine what can execute on this device. A missing executor is reported as not implemented; it is never described as an Android permission denial."
}
