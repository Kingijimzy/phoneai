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

    fun run(context: Context, proactiveGate: OrionProactiveGate.Decision? = null): TickResult {
        val c = context.applicationContext
        val coreEnabled = runCatching { AgentPolicy.enabled(c, "orion_core") }.getOrDefault(false)
        val proactiveEnabled = runCatching { AgentPolicy.enabled(c, "proactive") }.getOrDefault(false)
        // The standing purpose is durable and separate from any active user mission.
        runCatching { SoulEngineState.ensurePurpose(c, OrionInnerLife.PRIME_DIRECTIVE) }
        when {
            !coreEnabled -> OrionSoulLifecycle.stopped(c)
            !proactiveEnabled -> OrionSoulLifecycle.enter(c, OrionSoulLifecycle.State.PAUSED, "proactive_pack_disabled")
            else -> OrionSoulLifecycle.watcherTick(c)
        }
        // Reflection and inner-life work belong to the same watcher tick, not a parallel heartbeat.
        val reflectionResult = if (coreEnabled && proactiveEnabled) {
            runCatching { OrionLivingLoop.tick(c) }.getOrDefault("")
        } else ""
        val innerLifeResult = if (coreEnabled && proactiveEnabled) {
            runCatching { OrionInnerLife.tick(c) }.getOrDefault("")
        } else ""
        val service = PhoneAiService.instance
        // Force a fresh durable schedule read before reporting scheduler health; a stale
        // process-global storage-error flag is not evidence that disk is currently healthy.
        val schedulerOrders = runCatching { StandingOrders.all(c) }.getOrDefault(emptyList())
        val registered = runCatching { ToolRegistry.all.size }.getOrDefault(0)
        val implemented = AgentPolicy.packs.count { it.implemented }
        val enabled = AgentPolicy.packs.count { it.implemented && AgentPolicy.enabled(c, it.id) }
        val disclosure = runCatching { Prefs.accessibilityDisclosureAccepted(c) }.getOrDefault(false)
        val report = runCatching {
            OrionCapabilityAudit.evaluate(
                registeredTools = registered,
                implementedPacks = implemented,
                enabledPacks = enabled,
                accessibilityReady = service != null,
                screenDisclosureAccepted = disclosure,
                screenPerceptionReady = service != null && runCatching { service.rootInActiveWindow != null }.getOrDefault(false)
            )
        }.getOrElse {
            OrionCapabilityAudit.Report(
                status = OrionCapabilityAudit.Status.BLOCKED,
                registeredTools = registered,
                implementedPacks = implemented,
                enabledPacks = enabled,
                accessibilityReady = service != null,
                screenDisclosureAccepted = disclosure,
                screenPerceptionReady = false,
                limitations = listOf("capability_audit_exception")
            )
        }

        val schedulerStorageError = StandingOrders.storageError() != null
        val status = when {
            !coreEnabled || !proactiveEnabled -> "paused"
            schedulerStorageError -> "limited"
            report.status == OrionCapabilityAudit.Status.BLOCKED -> "blocked"
            report.status == OrionCapabilityAudit.Status.LIMITED -> "limited"
            else -> "ready"
        }
        val schedulerLimitations = if (schedulerStorageError) listOf("standing_order_storage_unavailable") else emptyList()
        val limitations = (report.limitations +
            (if (!coreEnabled) listOf("orion_core_disabled") else emptyList()) +
            (if (!proactiveEnabled) listOf("proactive_pack_disabled") else emptyList()) +
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

        // Stage 5: bounded local Propose–Probe–Compose pass. It performs registry-only dry runs,
        // is rate-limited to one automatic pass per six hours, and never executes candidate tools.
        val p3 = if (coreEnabled && proactiveEnabled) {
            runCatching { SoulP3Engine.run(c) }.getOrElse {
                SoulP3Engine.RunResult(0, 0, "Stage 5 probe unavailable: " + it.javaClass.simpleName + "; no candidate adopted.")
            }
        } else {
            SoulP3Engine.RunResult(0, 0, "Stage 5 probe skipped because Orion Core or Proactive is disabled.")
        }
        if (p3.probesRun > 0 || p3.compositionsCreated > 0) {
            runCatching { SoulEngineState.recordLesson(c, "p3_probe", p3.summary, "review_only") }
        }

        // Stage 6: bounded local specialists run sequentially under a 30-minute cooldown.
        // They only inspect local state and report findings; they never dispatch tools or mutate policy.
        val subagents = if (coreEnabled && proactiveEnabled) {
            runCatching { SoulSubagentCoordinator.run(c) }.getOrElse {
                SoulSubagentCoordinator.RunReport(
                    id = "unavailable",
                    at = System.currentTimeMillis(),
                    findings = emptyList(),
                    summary = "Subagent coordination unavailable: " + it.javaClass.simpleName + "; no actions taken."
                )
            }
        } else {
            SoulSubagentCoordinator.RunReport(
                id = "disabled",
                at = System.currentTimeMillis(),
                findings = emptyList(),
                summary = "Local specialists skipped because Orion Core or Proactive is disabled."
            )
        }

        // Start with the local work already completed; Chronos may refine this action label below.
        var action = if (p3.probesRun > 0) "local_capability_audit+p3_probe" else "local_capability_audit"
        if (subagents.findings.isNotEmpty()) action += "+subagents"
        if (reflectionResult.isNotBlank()) action += "+self_reflection"
        if (innerLifeResult.isNotBlank()) action += "+inner_life"

        // Chronos may create one low-pressure, review-only suggestion from coarse focus duration.
        // It never dispatches an action and is gated by both owner switches and ambient-context consent.
        val chronosSuggestion = runCatching { OrionChronosAwareness.proactiveSuggestion(c) }.getOrNull()
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

        // The proactive engine is now part of the real watcher path. Candidate selection is
        // side-effect free; delivery receipts are written only after NotificationManager accepts it.
        var proactiveDetail = ""
        val gate = proactiveGate ?: runCatching { OrionProactiveGate.evaluate(c) }
            .getOrElse { OrionProactiveGate.Decision(null, false, "proactive_gate_error") }
        val proactiveItem = if (coreEnabled && proactiveEnabled) gate.candidate else null
        if (proactiveItem != null) {
            val delivered = gate.allowDelivery &&
                runCatching { OrionRuntimeNotifier.postProactiveItem(c, proactiveItem) }.getOrDefault(false)
            if (delivered) {
                OrionProactiveEngine.markDelivered(c, proactiveItem)
                action += "+proactive_notification"
                proactiveDetail = "Delivered one ${proactiveItem.kind} reminder."
            } else {
                runCatching {
                    SoulEngineState.proposeWatchIssue(
                        c,
                        "proactive_delivery",
                        "Proactive reminder ready: ${proactiveItem.title}",
                        proactiveItem.body + " Delivery gate: " + gate.reason + ". Review this reminder in Orion."
                    )
                }
                action += "+proactive_pending"
                proactiveDetail = "A ${proactiveItem.kind} reminder is saved for review; proactive gate=" + gate.reason + "."
            }
        }

        // Scheduled external reads are also subject to the background Proactive Gate.
        // A due order is deferred, not silently dropped, when quiet hours or device state say wait.
        if (AgentPolicy.enabled(c, "orion_core") && AgentPolicy.enabled(c, "proactive")) {
            val hasDueOrder = StandingOrders.storageError() == null &&
                schedulerOrders.any { StandingOrderPolicy.due(it, System.currentTimeMillis()) }
            if (hasDueOrder && gate.allowStandingOrders) {
                val dispatched = runCatching { LifeLoop.tickOne(c) }.getOrDefault(0)
                action = if (dispatched > 0) "one_standing_order_checked_and_notified" else "one_standing_order_checked"
            } else if (hasDueOrder) {
                runCatching {
                    SoulEngineState.proposeWatchIssue(
                        c, "standing_order_deferred", "A scheduled Orion check is waiting.",
                        "The Proactive Gate deferred this check: " + gate.reason + ". It will be reconsidered on a later watcher tick."
                    )
                }
                action += "+standing_order_deferred"
            }
        }

        val next = when {
            !coreEnabled -> "Enable Orion Core explicitly to resume Soul planning."
            !proactiveEnabled -> "Enable the Proactive pack explicitly to resume scheduled Soul observations."
            limitations.isNotEmpty() -> "Review the Capability board; watcher will not grant permissions or enable packs."
            else -> "Continue bounded checks; review Stage 5 probe/composition reports before adopting any planning guidance."
        }
        runCatching { SoulEngineState.recordLesson(c, "watcher_tick", "Watcher action: $action; status=$status.", "observed") }
        val internalWork = listOf(reflectionResult, innerLifeResult).filter { it.isNotBlank() }.joinToString("; ")
        val nextStep = listOf(next, p3.summary, subagents.summary, internalWork, proactiveDetail)
            .filter { it.isNotBlank() }.joinToString(" ")
        runCatching { SoulEngineState.recordWatcherStatus(c, status, action, nextStep) }
        AgentPolicy.log(c, "soul_watcher", action, null, "status=$status; limitations=${limitations.joinToString(",").ifBlank { "none" }}")
        return TickResult(status, action, limitations, nextStep)
    }
}
