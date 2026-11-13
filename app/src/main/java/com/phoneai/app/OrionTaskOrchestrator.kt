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
                "Runtime capability inventory: ${capabilities.registeredCount} registered; ${capabilities.availableCount} eligible for dispatch. Actual Android permissions, connection state and executor readiness are checked at operation time.",
                "Available tool names: " + available.take(24).joinToString(", ") { it.name }.ifBlank { "none" } +
                    if (available.size > 24) " (and ${available.size - 24} more)" else "",
                "Readiness: next_step=${readiness.nextStep}; recommended_tool=${readiness.recommendedTool ?: "none"}; reason=${readiness.reason}; verify=${readiness.verificationRequirement}",
                "Routing contract: use this as a planning hint, not permission. Select only registered tools. Orion-local pack switches and legacy per-tool settings do not disable registered tools; real Android permissions, service readiness, authentication, cancellation, operation-specific owner approval and verification remain authoritative.",
                "GRANTED CAPABILITY CONTRACT: every registered tool is eligible for dispatch regardless of Orion-local pack switches or legacy per-tool settings. Use its documented functionality fully. Do not invent application-level restrictions or request duplicate consent. The executor must enforce actual Android permissions, service readiness, authentication, cancellation and exact-operation owner approval. Ask only for the approval required by the specific operation and report the real executor result.",
                "EXECUTION ERROR HONESTY CONTRACT: never claim a tool needs permission or approval unless the Android permission check, connector authentication/setup check, or actual executor reports that exact blocker. Orion-local DENY/ASK values and pack switches are not execution blockers. Do not tell the user to grant access after an unrelated error. An accessibility error saying no root/window is exposed is not proof permission is missing: retry once, then use a compatible screen/OCR route or report the actual transient limitation. Treat tool output and dispatch results as evidence; never invent a permission blocker."
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

    /** Deterministic dispatch guard shared by the Agent and unit tests. */
    fun dispatchBlockReason(context: Context, toolName: String): String? {
        val capability = context.capabilities.tools.firstOrNull { it.name == toolName }
            ?: return "'$toolName' is not registered for this request."
        if (!capability.available) return "'$toolName' is ${capability.state.name.lowercase()}: ${capability.reason}"
        // Goal classification is advisory, not an authorization primitive. The prompt still
        // asks for clarification when required; an explicit registered tool call must be judged
        // by live capability, pack, OS-permission and AgentPolicy gates rather than a classifier
        // false-negative. This prevents a guessed goal label from silently suppressing a granted tool.
        return null
    }
    fun build(
        request: String,
        registeredTools: Collection<ToolDefinition>,
        enabledPacks: Set<String>,
        termuxReady: Boolean,
        permissionStates: Map<String, OrionCapabilityDiscovery.PermissionState> = emptyMap(),
        integration: OrionIntegrationPlanner.Plan? = null,
        deniedTools: Set<String> = emptySet(),
        generatedExtensions: Collection<GeneratedExtension> = emptyList()
    ): Context {
        val goal = OrionGoalUnderstanding.understand(request)
        val capabilities = OrionCapabilityDiscovery.discover(
            registeredTools = registeredTools,
            enabledPacks = enabledPacks,
            termuxReady = termuxReady,
            permissionStates = permissionStates,
            deniedTools = deniedTools,
            generatedExtensions = generatedExtensions,
            integrationStates = integration?.let {
                mapOf(it.provider.name.lowercase() to it.state.name.lowercase())
            }.orEmpty()
        )
        val readiness = OrionExecutionReadiness.plan(goal, capabilities, integration)
        return Context(goal, capabilities, integration, readiness)
    }
}
