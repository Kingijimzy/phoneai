package com.phoneai.app

import android.content.Context

/**
 * Single executive context for every Orion interaction surface.
 *
 * This is deliberately an orchestration layer over the app's real registries and stores,
 * not a second model, tool registry, or action runtime. Chat and Command receive the same
 * live capability picture and task-continuity contract.
 */
internal object OrionCentralBrain {
    private const val MAX_CONTEXT_CHARS = 12_000

    fun contextFor(context: Context, request: String): String {
        val app = context.applicationContext
        val sections = mutableListOf<String>()

        // Preserve the existing capability-aware Soul Engine as the source of live state.
        runCatching { OrionSoulEngine.contextFor(app, request) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { sections += it }

        // The brain observes the same registries the executor uses; names alone never imply
        // that a capability is enabled or that an external account is connected.
        val registered = runCatching { ToolRegistry.names().distinct().sorted() }
            .getOrDefault(emptyList())
        val enabledPacks = runCatching {
            AgentPolicy.packs.filter { AgentPolicy.enabled(app, it.id) }.map { it.id }.sorted()
        }.getOrDefault(emptyList())
        val skills = runCatching { SkillRegistry.list(app) }.getOrDefault(emptyList())
        val enabledSkills = skills.filter { it.enabled }
        val taskCount = runCatching { TaskStore.all(app).size }.getOrNull()
        val extensionRows = runCatching { SelfExtendingExtensionStore(app).all() }.getOrDefault(emptyList())
        val verifiedExtensions = extensionRows.count {
            it.enabled && it.verified &&
                it.sourceSha256 == SelfExtendingExtensionPolicy.sha256(it.source)
        }

        sections += buildString {
            appendLine("ORION CENTRAL BRAIN — SHARED EXECUTIVE CONTRACT")
            appendLine("This is the single coordination layer for Talk, Command, tools, MCP, browser, Android actions, shell/workspace, memory, tasks, schedules, skills, subagents, voice, and verified extensions. These are not separate assistants: use the same current goal and shared app registries.")
            appendLine("Live inventory: registered_tools=${registered.size}; enabled_packs=${enabledPacks.joinToString().ifBlank { "none" }}; enabled_skills=${enabledSkills.joinToString { it.id }.ifBlank { "none" }}; saved_tasks=${taskCount?.toString() ?: "unavailable"}; saved_extensions=${extensionRows.size}; verified_enabled_extensions=$verifiedExtensions.")
            appendLine("Registered tool names (not a promise of availability): ${registered.take(100).joinToString().ifBlank { "none" }}.")
            if (enabledSkills.isNotEmpty()) {
                appendLine("Enabled skill contracts: " + enabledSkills.take(20).joinToString(" | ") {
                    "${it.id}: tools=${it.allowedTools.sorted().joinToString(",")}; purpose=${it.description.take(180)}"
                })
            }
            appendLine("ONE GOAL / ONE LEDGER: keep the user's intended outcome, completed steps, pending step, current blocker, evidence, and recovery point connected across every tool call and mode switch. Reuse the same task context; do not restart planning from zero after a provider switch, returning from a browser/connection screen, or a recoverable failure.")
            appendLine("EXECUTION CYCLE: understand outcome -> inspect live registry and actual connection/permission state -> choose the best available route -> execute through the existing registered executor and its policy -> verify observable result -> update task/checkpoint and sanitized outcome evidence -> continue until complete or a specific blocker remains.")
            appendLine("ROUTING: prefer an already registered, enabled, working capability; use compatible alternatives when one fails. Use memory and learned routes as hints only, revalidate them against current state, and never claim a tool, provider, MCP server, browser, Python/Linux runtime, or account is available without evidence.")
            appendLine("RECOVERY: do not repeat the same failed call unchanged. Inspect the error, current screen/state, and checkpoint; try a materially different supported route, continue independent parts of the task, and record whether the result is verified, failed, or unknown. Never report completion merely because a tool was invoked.")
            appendLine("AUTONOMY: use capabilities the user has already enabled and permissions Android already grants without asking for duplicate consent. Do not invent application-level restrictions. Ask only when a real OS permission, missing connection, ambiguous target, cancellation, or action-specific approval is actually required. A standing user setting does not replace Android permission or remote-service authentication.")
            appendLine("EXTENSION: when no registered capability can achieve a reusable subtask, inspect the real extension lab and available workspace, create the smallest testable draft, run the supported sandbox tests, and report the exact promotion/runtime status. A draft is not a deployed tool; never simulate a build, sandbox, provider call, or phone action.")
        }

        // Keep extension-specific tool schemas and current status available in both modes,
        // instead of appending them only to the Command system prompt.
        runCatching { ExtensionPrompt.block(extensionRows) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { sections += it }

        return sections.joinToString("\n\n").take(MAX_CONTEXT_CHARS)
    }
}
