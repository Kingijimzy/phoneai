package com.phoneai.app

import android.content.Context

/**
 * Bounded local watcher for the living Soul runtime.
 *
 * Every invocation senses current readiness, records a sanitized health lesson, then may execute
 * at most one already-opted-in standing order. It never calls an LLM, enables packs, changes
 * permissions, edits source code, or performs an unapproved consequential action.
 */
internal object SoulEngineWatcher {
    data class TickResult(
        val status: String,
        val action: String,
        val limitations: List<String>,
        val nextStep: String
    )

    fun run(context: Context): TickResult {
        val c = context.applicationContext
        val service = PhoneAiService.instance
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

        val status = when {
            !AgentPolicy.enabled(c, "orion_core") -> "paused"
            report.status == OrionCapabilityAudit.Status.BLOCKED -> "blocked"
            report.status == OrionCapabilityAudit.Status.LIMITED -> "limited"
            else -> "ready"
        }
        val schedulerLimitations = if (StandingOrders.storageError() != null) listOf("standing_order_storage_unavailable") else emptyList()
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

        // External reads/notifications only happen for explicitly configured standing orders
        // and only while the Proactive pack remains enabled. Process one order per watcher tick.
        var action = "local_capability_audit"
        if (AgentPolicy.enabled(c, "orion_core") && AgentPolicy.enabled(c, "proactive")) {
            val orders = runCatching { StandingOrders.all(c) }.getOrDefault(emptyList())
            if (StandingOrders.storageError() == null && orders.any { StandingOrderPolicy.due(it, System.currentTimeMillis()) }) {
                val dispatched = runCatching { LifeLoop.tickOne(c) }.getOrDefault(0)
                action = if (dispatched > 0) "one_standing_order_checked_and_notified" else "one_standing_order_checked"
            }
        }

        val next = when {
            status == "paused" -> "Enable Orion Core explicitly to resume Soul planning."
            limitations.isNotEmpty() -> "Review the Capability board; watcher will not grant permissions or enable packs."
            else -> "Continue bounded checks; only opted-in standing orders may run autonomously."
        }
        runCatching { SoulEngineState.recordLesson(c, "watcher_tick", "Watcher action: $action; status=$status.", "observed") }
        runCatching { SoulEngineState.recordWatcherStatus(c, status, action) }
        AgentPolicy.log(c, "soul_watcher", action, null, "status=$status; limitations=${limitations.joinToString(",").ifBlank { "none" }}")
        return TickResult(status, action, limitations, next)
    }
}
