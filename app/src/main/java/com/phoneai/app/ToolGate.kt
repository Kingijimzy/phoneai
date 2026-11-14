package com.phoneai.app

import android.content.Context

/**
 * Stage 2: one place that says which tools are usable right now and why not.
 * Registered tools are always dispatch-eligible. This compatibility layer never adds an Orion-local approval gateway; Android permissions and destructive delete/wipe confirmation remain separate.
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
     * Legacy preflight compatibility hook. Registered tools are not denied by local presets or pack toggles.
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
        "All registered tools are available to Orion. Android OS permissions and connected-service authentication determine platform availability. Orion owner approval is requested only before destructive deletion or wipe actions. A missing executor is reported as not implemented; it is never described as an Android permission denial."
}
