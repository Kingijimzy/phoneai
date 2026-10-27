package com.phoneai.app

/**
 * Converts goal framing + live capability facts into a bounded next-step recommendation.
 * It does not execute actions or grant approval; Agent's dispatch policy remains authoritative.
 */
internal object OrionExecutionReadiness {
    enum class NextStep {
        RESPOND, CLARIFY, EXECUTE_SUPPORTED_TOOL, OPEN_CONNECTION_SETUP,
        ENABLE_OR_INSTALL_CAPABILITY, PREPARE_SHELL_SETUP, RESEARCH_AND_PROPOSE
    }

    data class Plan(
        val nextStep: NextStep,
        val reason: String,
        val recommendedTool: String?,
        val verificationRequirement: String,
        val requiresApproval: Boolean
    ) {
        fun promptLines(): List<String> = listOf(
            "EXECUTION READINESS: next_step=${nextStep.name.lowercase().replace('_', '-')}; " +
                "recommended_tool=${recommendedTool ?: "none"}; requires_approval=$requiresApproval.",
            "Readiness reason: $reason",
            "Required verification: $verificationRequirement",
            "Execution constraint: this is a recommendation, not proof that any action ran. Dispatch policy and user approvals still apply."
        )
    }

    fun plan(
        goal: OrionGoalUnderstanding.Goal,
        capabilities: OrionCapabilityDiscovery.Snapshot,
        integration: OrionIntegrationPlanner.Plan? = null
    ): Plan {
        val available = capabilities.tools.filter { it.available }.map { it.name }.toSet()
        fun choose(vararg names: String): String? = names.firstOrNull { it in available }

        if (goal.kind == OrionGoalUnderstanding.Kind.CONVERSATION) {
            return Plan(NextStep.RESPOND, "The request is ordinary conversation; avoid unnecessary tools.", null,
                goal.verification, false)
        }
        if (goal.needsClarification) {
            return Plan(NextStep.CLARIFY, "The goal does not identify a sufficient target to act safely.", "ask_user",
                goal.verification, false)
        }

        if (integration != null && integration.provider != OrionIntegrationPlanner.Provider.UNKNOWN) {
            when (integration.nextStep) {
                OrionIntegrationPlanner.NextStep.USE_EXISTING ->
                    return Plan(NextStep.EXECUTE_SUPPORTED_TOOL,
                        "The integration planner reports a connected state and required capability; choose only a matching available tool.",
                        integrationTool(integration.provider, available), goal.verification, integration.needsUserAuthorization)
                OrionIntegrationPlanner.NextStep.ASK_TO_CONNECT -> {
                    val tool = choose("connect_service")
                    return if (tool != null) Plan(NextStep.OPEN_CONNECTION_SETUP,
                        "The provider is not connected but the secure connection setup tool is available.", tool,
                        "Return from setup and re-check actual connection state and granted scopes.", true)
                    else Plan(NextStep.ENABLE_OR_INSTALL_CAPABILITY,
                        "Connection setup is required but no registered setup tool is currently available.", choose("capability_note"),
                        "Verify the setup capability is available before promising to open it.", true)
                }
                OrionIntegrationPlanner.NextStep.ASK_TO_ENABLE_OR_INSTALL ->
                    return Plan(NextStep.ENABLE_OR_INSTALL_CAPABILITY,
                        "The integration planner found a missing capability or prerequisite.", choose("capability_note"),
                        "Re-run discovery after the user enables or installs the prerequisite.", true)
                OrionIntegrationPlanner.NextStep.RESEARCH_AND_PROPOSE ->
                    return Plan(NextStep.RESEARCH_AND_PROPOSE,
                        "The requested integration is not proven usable; inspect available source/research tools and report the concrete gap.",
                        choose("live_web_search", "research_web", "fetch_url", "capability_note"),
                        "Cite observed tool/connection state and distinguish research from implementation.", false)
                OrionIntegrationPlanner.NextStep.CLARIFY -> Unit
            }
        }

        if (goal.kind == OrionGoalUnderstanding.Kind.SHELL_EXECUTION) {
            val shell = choose("shell", "termux_command")
            if (shell != null) return Plan(NextStep.EXECUTE_SUPPORTED_TOOL,
                "A shell entry point is registered and currently available; obey its allowlist and approval rules.",
                shell, "Inspect command output and exit/result status; never infer task completion from dispatch alone.", true)
            return Plan(NextStep.PREPARE_SHELL_SETUP,
                "No shell tool is currently available. Check the Termux bridge/workspace pack and its readiness before claiming execution.",
                choose("connect_service", "capability_note"), "Re-run discovery and verify shell readiness before executing.", true)
        }

        val candidates = when (goal.kind) {
            OrionGoalUnderstanding.Kind.ACCOUNT_CONNECT -> listOf("connect_service", "integration_status", "service_status")
            OrionGoalUnderstanding.Kind.ACCOUNT_DATA -> listOf("github_account_read", "gmail_list_messages", "integration_status", "service_status")
            OrionGoalUnderstanding.Kind.CODING -> listOf("workspace_read", "workspace_write", "shell", "github_account_read")
            OrionGoalUnderstanding.Kind.RESEARCH -> listOf("live_web_search", "live_news", "weather", "research_web", "fetch_url")
            OrionGoalUnderstanding.Kind.DEVICE_ACTION -> listOf("read_screen", "open_app", "system_intent", "settings")
            OrionGoalUnderstanding.Kind.STANDING_ORDER -> listOf("standing_order", "task_list", "task_add")
            else -> listOf("task_list", "ask_agent", "live_web_search", "workspace_read")
        }
        val tool = choose(*candidates.toTypedArray())
        if (tool != null) return Plan(NextStep.EXECUTE_SUPPORTED_TOOL,
            "At least one matching registered tool is currently available. Use the narrowest suitable tool and verify its returned evidence.",
            tool, goal.verification, ToolRegistry.access(tool) == ToolAccess.WRITE)
        return Plan(NextStep.RESEARCH_AND_PROPOSE,
            "No matching tool is currently available in the live capability snapshot; do not claim the task was executed.",
            choose("capability_note", "live_web_search", "service_status"),
            "Identify the exact missing pack, permission, connection, or tool; then re-run discovery.", false)
    }

    private fun integrationTool(provider: OrionIntegrationPlanner.Provider, available: Set<String>): String? {
        val candidates = when (provider) {
            OrionIntegrationPlanner.Provider.GITHUB -> listOf("github_account_read", "github_public")
            OrionIntegrationPlanner.Provider.GMAIL -> listOf("gmail_list_messages")
            OrionIntegrationPlanner.Provider.GOOGLE_DRIVE -> listOf("google_drive_files_read", "google_drive_list_files")
            OrionIntegrationPlanner.Provider.TELEGRAM -> listOf("telegram_inbox", "telegram_status")
            OrionIntegrationPlanner.Provider.TERMUX -> listOf("termux_command", "shell")
            OrionIntegrationPlanner.Provider.UNKNOWN -> emptyList()
        }
        return candidates.firstOrNull { it in available }
    }
}
