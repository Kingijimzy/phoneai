package com.phoneai.app

/**
 * Final request-time coordinator for the existing goal, capability, integration, and readiness
 * components. This creates a bounded planning context; Agent's dispatch and approval gates remain
 * authoritative and the model cannot treat a recommendation as execution proof.
 */
internal object OrionTaskOrchestrator {
    data class Context(
        val goal: OrionGoalUnderstanding.Goal,
        val capabilities: OrionCapabilityDiscovery.Snapshot,
        val integration: OrionIntegrationPlanner.Plan?,
        val readiness: OrionExecutionReadiness.Plan
    ) {
        fun promptLines(): List<String> {
            val available = capabilities.tools.filter { it.available }
            val blockers = capabilities.tools.filterNot { it.available }
                .take(6)
                .joinToString("; ") { "${it.name}: ${it.reason}" }
            val lines = mutableListOf(
                "ORION TASK COORDINATOR (fresh for this request):",
                "Goal: kind=${goal.kind}; outcome=${goal.requestedOutcome}; target=${goal.target.ifBlank { "not confidently identified" }}; should_act=${goal.shouldAct}; needs_clarification=${goal.needsClarification}.",
                "Runtime capability inventory: ${capabilities.registeredCount} registered; ${capabilities.availableCount} available after enabled-pack and known-prerequisite checks.",
                "Available tool names: " + available.take(24).joinToString(", ") { it.name }.ifBlank { "none" } +
                    if (available.size > 24) " (and ${available.size - 24} more)" else "",
                "Readiness: next_step=${readiness.nextStep}; recommended_tool=${readiness.recommendedTool ?: "none"}; reason=${readiness.reason}; verify=${readiness.verificationRequirement}",
                "Goal loop: preserve the same requested outcome across tool results; update the next action from evidence rather than restarting the task.",
                "Recovery rule: permit one materially different safe recovery after a recoverable failure; never repeat a failed call or retry an ambiguous consequential action.",
                "Routing contract: use this as a planning hint, not permission. Only select tools present in the actual tool registry; dispatch policy, pack enablement, Android permissions, confirmation, cancellation, and verification remain mandatory."
            )
            if (goal.needsClarification) lines += "Required next action: ask one focused clarification; do not dispatch a tool."
            if (integration != null) {
                lines += "Integration assessment: provider=${integration.provider}; state=${integration.state}; next_step=${integration.nextStep}; explanation=${integration.explanation}"
                if (integration.requiredCapabilities.isNotEmpty()) {
                    lines += "Integration requirements: ${integration.requiredCapabilities.joinToString(", ")}"
                }
            }
            if (blockers.isNotBlank()) lines += "Sample unavailable-tool reasons: $blockers"
            if (capabilities.blockers.isNotEmpty()) lines += "Runtime blockers: ${capabilities.blockers.take(4).joinToString("; ")}"
            return lines
        }
    }

    fun build(
        request: String,
        registeredTools: Collection<ToolDefinition>,
        enabledPacks: Set<String>,
        permissionStates: Map<String, OrionCapabilityDiscovery.PermissionState> = emptyMap(),
        integration: OrionIntegrationPlanner.Plan? = null
    ): Context {
        val goal = OrionGoalUnderstanding.understand(request)
        val capabilities = OrionCapabilityDiscovery.discover(
            registeredTools = registeredTools,
            enabledPacks = enabledPacks,
            permissionStates = permissionStates,
            integrationStates = integration?.let {
                mapOf(it.provider.name.lowercase() to it.state.name.lowercase())
            }.orEmpty()
        )
        val readiness = OrionExecutionReadiness.plan(goal, capabilities, integration)
        return Context(goal, capabilities, integration, readiness)
    }
}
