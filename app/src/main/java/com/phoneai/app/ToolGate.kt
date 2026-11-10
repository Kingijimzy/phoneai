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
    ): String = ""

    /** Generic pack and per-tool permission gates are retired. Registry membership is checked by dispatch. */
    fun preflightDenial(c: Context, tool: String): String? = null

    /** Where the user switches a pack on. Kept in one place so the model and the screens agree. */
    fun howToEnable(pack: String): String = when (pack) {
        "connected_services" -> "the Capability board (Connected services)"
        "workspace_shell" -> "Agent control > Packs > Workspace shell"
        "orion_core" -> "Agent control > Packs > Orion core"
        else -> "Agent control > Packs"
    }

    /** The live status for the system prompt, built from the same registry rule the pre-flight gate uses. */
    fun statusFor(c: Context): String =
        "PARTNER OPERATION: no generic app-level tool modes or capability-pack switches. Registered tools are available to Orion; actual Android OS permissions, integration connection state, domain-specific Trading/Coach controls, and destructive-action confirmation still apply."
}
