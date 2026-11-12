package com.phoneai.app

import android.content.Context

/**
 * Bounded local watcher for the living Soul runtime.
 *
 * Every invocation senses current readiness, records a sanitized health lesson, then may execute
 * at most one already-opted-in standing order. It never calls an LLM, enables packs, changes
 * permissions, edits source code, or performs an unapproved consequential action.
 */
internal object OrionContinuousWatcherPolicy {
    const val COOLDOWN_MS = 30_000L

    fun shouldObserve(
        coreEnabled: Boolean,
        proactiveEnabled: Boolean,
        serviceConnected: Boolean,
        elapsedSinceLastObservationMs: Long
    ): Boolean = coreEnabled && proactiveEnabled && serviceConnected &&
        elapsedSinceLastObservationMs >= COOLDOWN_MS
}

internal object SoulEngineWatcher {
    data class TickResult(
        val status: String,
        val action: String,
        val limitations: List<String>,
        val nextStep: String
    )

    fun run(context: Context, allowUnpromptedInitiative: Boolean = true): TickResult {
        val c = context.applicationContext
        // Genuine lifecycle state: the watcher tick means the Soul is monitoring.
        OrionSoulLifecycle.watcherTick(c)
        // Persist the capability baseline so later watcher ticks can identify additions/removals.
        // A read-only state() call alone cannot discover deltas unless scan() advances this baseline.
        val capabilityAwareness = runCatching { OrionCapabilityAwareness.scan(c) }
            .getOrElse { OrionCapabilityAwareness.State(emptyList(), emptyList(), emptyList(), emptyList()) }
        val awarenessSummary = buildList {
            if (capabilityAwareness.added.isNotEmpty()) add("new capabilities: ${capabilityAwareness.added.take(8).joinToString()}")
            if (capabilityAwareness.removed.isNotEmpty()) add("missing capabilities: ${capabilityAwareness.removed.take(8).joinToString()}")
            if (capabilityAwareness.failing.isNotEmpty()) add("degraded tools: ${capabilityAwareness.failing.take(5).joinToString()}")
        }.joinToString("; ")
        val service = PhoneAiService.instance
        // Force a fresh durable schedule read before reporting scheduler health; a stale
        // process-global storage-error flag is not evidence that disk is currently healthy.
        val schedulerOrders = runCatching { StandingOrders.all(c) }.getOrDefault(emptyList())
        val registered = runCatching { ToolRegistry.all.size }.getOrDefault(0)
        val implemented = AgentPolicy.packs.count { it.implemented }
        val enabled = AgentPolicy.packs.count { it.implemented && AgentPolicy.enabled(c, it.id) }
        val report = runCatching {
            OrionCapabilityAudit.evaluate(
                registeredTools = registered,
                implementedPacks = implemented,
                enabledPacks = enabled,
                accessibilityReady = service != null,
                screenPerceptionReady = service != null && runCatching { service.rootInActiveWindow != null }.getOrDefault(false)
            )
        }.getOrElse {
            OrionCapabilityAudit.Report(
                status = OrionCapabilityAudit.Status.BLOCKED,
                registeredTools = registered,
                implementedPacks = implemented,
                enabledPacks = enabled,
                accessibilityReady = service != null,
                screenPerceptionReady = false,
                limitations = listOf("capability_audit_exception")
            )
        }

        val schedulerStorageError = StandingOrders.storageError() != null
        val status = when {
            !AgentPolicy.enabled(c, "orion_core") -> "paused"
            schedulerStorageError -> "limited"
            report.status == OrionCapabilityAudit.Status.BLOCKED -> "blocked"
            report.status == OrionCapabilityAudit.Status.LIMITED -> "limited"
            else -> "ready"
        }
        val schedulerLimitations = if (schedulerStorageError) listOf("standing_order_storage_unavailable") else emptyList()
        val limitations = (report.limitations +
            (if (!AgentPolicy.enabled(c, "orion_core")) listOf("orion_core_disabled") else emptyList()) +
            schedulerLimitations).distinct()
        val summary = "Watcher readiness=$status; registered_tools=$registered; enabled_packs=$enabled; limitations=${limitations.joinToString(",").ifBlank { "none" }}."
        runCatching { SoulEngineState.recordLesson(c, "watcher_health", summary, status) }
        if (limitations.isNotEmpty() && AgentPolicy.enabled(c, "orion_core")) {
            val issue = limitations.first()
            runCatching {
                SoulEngineState.proposeWatchIssue(
                    c,
                    "readiness",
                    "Soul watcher found: $issue",
                    "Inspect the Capability board and repair the underlying setup or service condition. Re-run the local capability audit to verify the result."
                )
            }
        }

        // Cerebro watcher: repair the local graph index from currently approved, active memories.
        // It is throttled, device-local and read-only with respect to the source memories.
        val graphWatch = runCatching { OrionKnowledgeGraphWatcher.run(c) }.getOrElse {
            OrionKnowledgeGraphWatcher.Report(0, 0, 1, "knowledge_graph_reconcile_failed:" + it.javaClass.simpleName)
        }
        if (graphWatch.indexedMemories > 0 || graphWatch.failures > 0) {
            runCatching { SoulEngineState.recordLesson(c, "knowledge_graph_watch", graphWatch.summary, if (graphWatch.failures == 0) "observed" else "limited") }
        }

        // Stage 5: bounded local Propose–Probe–Compose pass. It performs registry-only dry runs,
        // is rate-limited to one automatic pass per six hours, and never executes candidate tools.
        val p3 = runCatching { SoulP3Engine.run(c) }.getOrElse {
            SoulP3Engine.RunResult(0, 0, "Stage 5 probe unavailable: " + it.javaClass.simpleName + "; no candidate adopted.")
        }
        if (p3.probesRun > 0 || p3.compositionsCreated > 0) {
            runCatching { SoulEngineState.recordLesson(c, "p3_probe", p3.summary, "review_only") }
        }

        // Stage 6: bounded local specialists run sequentially under a 30-minute cooldown.
        // They only inspect local state and report findings; they never dispatch tools or mutate policy.
        val subagents = runCatching { SoulSubagentCoordinator.run(c) }.getOrElse {
            SoulSubagentCoordinator.RunReport(
                id = "unavailable",
                at = System.currentTimeMillis(),
                findings = emptyList(),
                summary = "Subagent coordination unavailable: " + it.javaClass.simpleName + "; no actions taken."
            )
        }

        // Start with the local work already completed; Chronos may refine this action label below.
        var action = if (p3.probesRun > 0) "local_capability_audit+p3_probe" else "local_capability_audit"
        if (graphWatch.indexedMemories > 0 || graphWatch.failures > 0) action += "+knowledge_graph_watch"
        if (subagents.findings.isNotEmpty()) action += "+subagents"

        // Chronos may create one low-pressure, review-only suggestion from coarse focus duration.
        // It never dispatches an action and is gated by both owner switches and ambient-context consent.
        val chronosSuggestion = if (allowUnpromptedInitiative) runCatching { OrionChronosAwareness.proactiveSuggestion(c) }.getOrNull() else null
        if (!chronosSuggestion.isNullOrBlank()) {
            val category = OrionChronosAwareness.summary(c).substringAfter("category=").substringBefore(";")
            val proposed = runCatching {
                SoulEngineState.proposeWatchIssue(
                    c, "chronos",
                    "Chronos: optional break after extended $category focus",
                    chronosSuggestion
                )
            }.getOrDefault(false)
            if (proposed && OrionChronosAwareness.markSuggestionShown(c)) action = "chronos_review_suggestion"
        }

        // External reads/notifications only happen for explicitly configured standing orders
        // and only while the Proactive pack remains enabled. Process one order per watcher tick.
        if (AgentPolicy.enabled(c, "orion_core") && AgentPolicy.enabled(c, "proactive")) {
            if (StandingOrders.storageError() == null && schedulerOrders.any { StandingOrderPolicy.due(it, System.currentTimeMillis()) }) {
                val dispatched = runCatching { LifeLoop.tickOne(c) }.getOrDefault(0)
                action = if (dispatched > 0) "one_standing_order_checked_and_notified" else "one_standing_order_checked"
            }
        }

        val next = when {
            status == "paused" -> "Enable Orion Core explicitly to resume Soul planning."
            limitations.isNotEmpty() -> "Review the Capability board; watcher will not grant permissions or enable packs."
            else -> "Continue bounded checks; review Stage 5 probe/composition reports before adopting any planning guidance."
        }
        runCatching { SoulEngineState.recordLesson(c, "watcher_tick", "Watcher action: $action; status=$status.", "observed") }
        runCatching { SoulEngineState.recordWatcherStatus(c, status, action) }
        AgentPolicy.log(c, "soul_watcher", action, null, "status=$status; limitations=${limitations.joinToString(",").ifBlank { "none" }}")
        return TickResult(status, action, limitations, next + " " + awarenessSummary + " " + p3.summary + " " + subagents.summary)
    }
}
