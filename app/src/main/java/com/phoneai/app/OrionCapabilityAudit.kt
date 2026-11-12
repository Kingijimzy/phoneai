package com.phoneai.app

/**
 * Local capability/perception audit. Reports live availability; it never grants permissions,
 * enables packs, or treats a model request as owner consent.
 */
internal object OrionCapabilityAudit {
    enum class Status { READY, LIMITED, BLOCKED }

    data class Report(
        val status: Status,
        val registeredTools: Int,
        val implementedPacks: Int,
        val enabledPacks: Int,
        val accessibilityReady: Boolean,
        val screenPerceptionReady: Boolean,
        val shizukuReady: Boolean,
        val limitations: List<String>
    )

    fun evaluate(
        registeredTools: Int,
        implementedPacks: Int,
        enabledPacks: Int,
        accessibilityReady: Boolean,
        screenPerceptionReady: Boolean,
        shizukuReady: Boolean = false
    ): Report {
        val limitations = mutableListOf<String>()
        if (registeredTools <= 0) limitations += "no_registered_tools"
        if (implementedPacks <= 0) limitations += "no_implemented_capability_packs"
        if (enabledPacks <= 0) limitations += "no_enabled_capability_packs"
        if (!accessibilityReady) limitations += "accessibility_service_unavailable"
        if (!screenPerceptionReady) limitations += "screen_perception_unavailable"
        if (!shizukuReady) limitations += "shizuku_not_ready_optional"

        val status = when {
            registeredTools <= 0 || implementedPacks <= 0 || !accessibilityReady -> Status.BLOCKED
            enabledPacks <= 0 || !screenPerceptionReady -> Status.LIMITED
            else -> Status.READY
        }
        return Report(
            status = status,
            registeredTools = registeredTools.coerceAtLeast(0),
            implementedPacks = implementedPacks.coerceAtLeast(0),
            enabledPacks = enabledPacks.coerceIn(0, implementedPacks.coerceAtLeast(0)),
            accessibilityReady = accessibilityReady,
            screenPerceptionReady = screenPerceptionReady,
            shizukuReady = shizukuReady,
            limitations = limitations.distinct()
        )
    }
}
