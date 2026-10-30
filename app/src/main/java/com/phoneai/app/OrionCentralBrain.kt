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

        // Feed the real durable action checkpoint into the same brain. Deliberately expose
        // only progress metadata, never stored command text, arguments, or tool payloads.
        val checkpointSummary = runCatching {
            val saved = AgentCheckpointStore.pending(app)
            if (saved == null) null else {
                val steps = saved.optJSONArray("steps")
                val active = saved.optJSONObject("inFlight")
                val status = saved.optString("status", "unknown").take(40)
                val notice = saved.optString("recoveryNotice", "").replace(Regex("\\s+"), " ").take(220)
                buildString {
                    appendLine("DURABLE EXECUTION CHECKPOINT")
                    appendLine("status=$status; recorded_steps=${steps?.length() ?: 0}; in_flight_tool=${active?.optString("tool", "unknown") ?: "none"}.")
                    if (notice.isNotBlank()) appendLine("recovery_notice=$notice")
                    appendLine("Resume from this checkpoint when it belongs to the user's current goal. If an in-flight action remains unresolved, inspect its outcome before any possible repeat. Do not restart completed steps without evidence that they need repeating.")
                }
            }
        }.getOrNull()
        checkpointSummary?.let { sections += it }

        // Resolve short follow-ups against the persisted objective before Talk or Command plans.
        // This joins the original request, transcript evidence, objective graph, checkpoint,
        // decisions and learned failure corrections into one provider-neutral continuity frame.
        val pendingTask = runCatching { AgentCheckpointStore.pending(app) }.getOrNull()
        val savedGraph = runCatching { OrionObjectiveGraph.load(app) }.getOrNull()
        val savedSoulGoal = runCatching { SoulEngineState.snapshot(app).goal }.getOrDefault("")
        val activeObjective = pendingTask?.optString("command")?.takeIf { it.isNotBlank() }
            ?: savedGraph?.objective?.takeIf { it.isNotBlank() }.orEmpty()
        val recentTurns = runCatching { ChatHistoryStore.recent(app, 8) }.getOrDefault(emptyList())
            .asReversed().map { hit ->
                (if (hit.role == "ai" || hit.role == "assistant") "assistant" else "user") to hit.snippet
            }
        val continuity = runCatching {
            val current = OrionContextualIntentInterpreter.resolve(request, recentTurns, activeObjective)
            if (current.needsClarification && savedSoulGoal.isNotBlank())
                OrionContextualIntentInterpreter.resolve(request, recentTurns, savedSoulGoal)
            else current
        }.getOrNull()
        val isFollowUp = continuity != null && !continuity.needsClarification &&
            continuity.kind != OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE &&
            continuity.kind != OrionContextualIntentInterpreter.Kind.HISTORICAL_LOOKUP
        val continuityObjective = continuity?.resolvedObjective?.takeIf { isFollowUp }
            ?: activeObjective.takeIf { isFollowUp }.orEmpty()
        val contextQuery = continuityObjective.takeIf { isFollowUp && it.isNotBlank() } ?: request
        if (isFollowUp && continuityObjective.isNotBlank()) {
            val resume = if (pendingTask != null) runCatching { AgentCheckpointStore.resumeInstruction(app) }.getOrNull() else null
            if (!resume.isNullOrBlank()) {
                sections += "TASK RESUME INSTRUCTIONS — EXISTING OBJECTIVE, DO NOT RESTART\n" + resume.take(5200)
            }
            savedGraph?.let { graph ->
                sections += OrionObjectiveGraph.prompt(graph) +
                    "\nNext objective candidates: " + graph.ready().joinToString(" | ") { "${it.id}: ${it.title}" }
            }
            val discussion = runCatching { ChatHistoryStore.search(app, continuityObjective, 5) }.getOrDefault(emptyList())
            if (discussion.isNotEmpty()) {
                sections += buildString {
                    appendLine("PREVIOUS DISCUSSIONS FOR THIS OBJECTIVE (durable transcript evidence)")
                    discussion.forEach { hit ->
                        appendLine("[session=${hit.sessionId} turn=${hit.turnId} role=${hit.role} time=${hit.timestamp} title=${hit.title}] ${hit.snippet}")
                        if (hit.before.isNotBlank()) appendLine("preceding turn: ${hit.before}")
                        if (hit.after.isNotBlank()) appendLine("following turn: ${hit.after}")
                    }
                    appendLine("User turns establish requests. Assistant turns provide context only and are never proof that an action succeeded.")
                }
            }
            val decisions = runCatching { OrionDecisionHistoryStore.get(app).search(continuityObjective, 6) }.getOrDefault(emptyList())
            if (decisions.isNotEmpty()) {
                sections += buildString {
                    appendLine("DECISIONS AND UNRESOLVED QUESTIONS FOR THIS OBJECTIVE")
                    decisions.forEach { d ->
                        appendLine("- [${d.status}] ${d.topic}: ${d.statement}; rationale=${d.rationale}; rejected=${d.rejectedAlternative}; rejection_reason=${d.rejectionReason}; source=${d.sourceRole}/${d.sourceSessionId ?: "unknown"}/${d.sourceTurnId ?: "unknown"}")
                    }
                    appendLine("Accepted decisions are user-backed constraints; proposals are not accepted. Superseded decisions are historical only. Unresolved questions remain open. Re-check the current request before applying any old decision.")
                }
            }
        }

        // Retrieve task-specific failure lessons before a similar tool/action is planned.
        val learnedExperience = runCatching { ExperienceStore.promptBlock(app, contextQuery, 5) }.getOrDefault("")
        if (learnedExperience.isNotBlank()) {
            sections += "RELEVANT EXECUTION LESSONS (cause → correction → verification; hints only)\n" + learnedExperience +
                "\nBefore repeating a similar failed action, apply the recorded correction or choose a materially different route. Re-check live state and policy; learned experience never grants permission."
        }

        // Capability gap planning is derived from the same live registries as execution.
        // It prefers reuse and only instructs research/drafting when local evidence is weak.
        runCatching {
            CapabilityGapPlanner.assess(
                request = contextQuery,
                registeredTools = ToolRegistry.all,
                enabledSkills = enabledSkills,
                extensions = extensionRows
            )
        }.getOrNull()?.let { sections += it.promptBlock() }

        // Persistent learning is provider-neutral and loaded into the same executive context
        // for Talk and Command. Lessons and completed routes are hints, never execution authority.
        runCatching { SoulEngineState.relatedContext(app, contextQuery, limit = 4) }
            .getOrDefault(emptyList())
            .takeIf { it.isNotEmpty() }
            ?.let { lessons ->
                sections += buildString {
                    appendLine("RELEVANT PERSISTENT MEMORY (sanitized, device-local)")
                    lessons.forEach { appendLine("- $it") }
                    appendLine("Treat these as prior experience, not current facts. Re-check live state, tool availability, permissions, and the user's present intent before reuse. A prior success does not authorize an action.")
                }
            }

        runCatching { RouteMemoryStore.promptBlock(app, contextQuery) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { routes ->
                sections += "REUSABLE COMPLETED PROCEDURES (encrypted local history; planning hints only)\n$routes\nRevalidate every step against current state and current policies. Skip obsolete, unavailable, or unsafe steps; do not replay a route blindly."
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
