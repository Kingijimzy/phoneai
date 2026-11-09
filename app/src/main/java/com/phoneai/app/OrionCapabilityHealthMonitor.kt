package com.phoneai.app

import android.content.Context
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 0D: Capability Health Monitor
 *
 * Continuously monitors the health of all Orion capabilities and triggers autonomous
 * recovery when capabilities degrade or fail. Integrates with OrionCapabilityIntelligence
 * for discovery and OrionCapabilityAcquisitionEngine for remediation.
 *
 * Embodies the doctrine:
 *   - "When something fails, Orion investigates, changes strategy and continues rather than
 *     repeatedly attempting the same failed action."
 *   - "Do not treat a registry name as proof of successful execution — verify each action."
 *
 * Health states flow: UNKNOWN → HEALTHY → DEGRADED → CRITICAL → BLOCKED
 * When a capability degrades, the monitor attempts recovery before reporting failure.
 */
internal class OrionCapabilityHealthMonitor(
    private val context: Context,
    private val svc: PhoneAiService,
    private val capabilityIntelligence: OrionCapabilityIntelligence,
    private val acquisitionEngine: OrionCapabilityAcquisitionEngine,
    private val capabilityBroker: OrionCapabilityBroker?
) {

    private val healthStatus = ConcurrentHashMap<String, CapabilityHealthStatus>()
    private val monitoringJob: Job? = null
    private val isMonitoring = AtomicBoolean(false)
    private val healthCheckIntervalMs = 5 * 60 * 1000L // 5 minutes
    private val degradationThreshold = 0.7  // success rate below this = degraded
    private val criticalThreshold = 0.4     // below this = critical

    companion object {
        @Volatile private var INSTANCE: OrionCapabilityHealthMonitor? = null
        fun getInstance(
            context: Context,
            svc: PhoneAiService,
            capabilityIntelligence: OrionCapabilityIntelligence,
            acquisitionEngine: OrionCapabilityAcquisitionEngine,
            capabilityBroker: OrionCapabilityBroker?
        ): OrionCapabilityHealthMonitor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OrionCapabilityHealthMonitor(
                    context, svc, capabilityIntelligence, acquisitionEngine, capabilityBroker
                ).also { INSTANCE = it }
            }
        }
    }

    /**
     * Start continuous background health monitoring.
     */
    fun startMonitoring(scope: CoroutineScope) {
        if (isMonitoring.getAndSet(true)) return
        scope.launch {
            while (isMonitoring.get()) {
                try {
                    runHealthCheckCycle()
                } catch (e: Exception) {
                    // Monitoring errors should not crash the app
                    OrionEvidenceLedger.recordDispatch(
                        svc, "health_monitor", "health_check_error", "health_check_error",
                        org.json.JSONObject().put("error", e.message.orEmpty()),
                        System.currentTimeMillis(),
                        "monitoring continues"
                    )
                }
                delay(healthCheckIntervalMs)
            }
        }
    }

    /** Stop monitoring. */
    fun stopMonitoring() {
        isMonitoring.set(false)
    }

    /**
     * Run one health check cycle: assess all capabilities, recover degraded ones.
     */
    private suspend fun runHealthCheckCycle() {
        // Refresh health assessments from capability intelligence
        val ecosystem = capabilityIntelligence.discoverFullCapabilityEcosystem()

        for (cap in ecosystem.allCapabilities) {
            val health = ecosystem.healthAssessments[cap.id]
            if (health == null) continue

            val previous = healthStatus[cap.id]
            val current = toHealthStatus(cap, health)
            healthStatus[cap.id] = current

            // Detect degradation and trigger recovery
            if (previous != null && previous.status != current.status) {
                onStatusChanged(cap, previous.status, current.status, health)
            }

            // Auto-recover degraded/critical capabilities
            if (current.status == CapabilityStatus.DEGRADED || current.status == CapabilityStatus.CRITICAL) {
                attemptRecovery(cap, current, health)
            }
        }
    }

    /**
     * Check health of a single capability on demand.
     */
    suspend fun checkCapabilityHealth(capabilityId: String): CapabilityHealthStatus {
        val ecosystem = capabilityIntelligence.discoverFullCapabilityEcosystem()
        val cap = ecosystem.allCapabilities.firstOrNull { it.id == capabilityId }
        if (cap == null) {
            return CapabilityHealthStatus(capabilityId, CapabilityStatus.MISSING, "Not discovered")
        }
        val health = ecosystem.healthAssessments[capabilityId]
        if (health == null) {
            return CapabilityHealthStatus(capabilityId, CapabilityStatus.UNKNOWN, "No health data")
        }
        val status = toHealthStatus(cap, health)
        healthStatus[capabilityId] = status
        return status
    }

    /** Get current health status for a capability. */
    fun getHealthStatus(capabilityId: String): CapabilityHealthStatus? =
        healthStatus[capabilityId]

    /** Get all health statuses. */
    fun getAllHealthStatuses(): Map<String, CapabilityHealthStatus> =
        healthStatus.toMap()

    /** Count capabilities by status. */
    fun healthSummary(): Map<CapabilityStatus, Int> {
        val counts = mutableMapOf<CapabilityStatus, Int>()
        CapabilityStatus.values().forEach { counts[it] = 0 }
        healthStatus.values.forEach { counts[it.status] = (counts[it.status] ?: 0) + 1 }
        return counts
    }

    // ========================================================================
    // Recovery logic
    // ========================================================================

    private suspend fun onStatusChanged(
        cap: DiscoveredCapability,
        previous: CapabilityStatus,
        current: CapabilityStatus,
        health: CapabilityHealth
    ) {
        val change = when {
            previous == CapabilityStatus.HEALTHY && current == CapabilityStatus.DEGRADED ->
                "DEGRADED: ${cap.name} success rate dropped below 70%"
            previous == CapabilityStatus.DEGRADED && current == CapabilityStatus.CRITICAL ->
                "CRITICAL: ${cap.name} success rate dropped below 40%"
            previous in setOf(CapabilityStatus.DEGRADED, CapabilityStatus.CRITICAL) &&
                current == CapabilityStatus.HEALTHY ->
                "RECOVERED: ${cap.name} is healthy again"
            previous == CapabilityStatus.BLOCKED && current != CapabilityStatus.BLOCKED ->
                "UNBLOCKED: ${cap.name} is now ${current.name}"
            else -> null
        }
        if (change != null) {
            OrionEvidenceLedger.recordDispatch(
                svc, "health_monitor", "status_change", "status_change",
                org.json.JSONObject()
                    .put("capability", cap.id)
                    .put("previous", previous.name)
                    .put("current", current.name)
                    .put("change", change),
                System.currentTimeMillis(),
                change
            )
        }
    }

    private suspend fun attemptRecovery(
        cap: DiscoveredCapability,
        status: CapabilityHealthStatus,
        health: CapabilityHealth
    ) {
        // Avoid rapid re-recovery attempts
        val lastAttempt = healthStatus[cap.id]?.lastRecoveryAttempt ?: 0L
        if (System.currentTimeMillis() - lastAttempt < 60_000) return // 1 minute cooldown
        healthStatus[cap.id] = status.copy(lastRecoveryAttempt = System.currentTimeMillis())

        val recoveryPlan = when {
            health.status == CapabilityStatus.BLOCKED ->
                // Try to resolve: enable pack, grant permission
                resolveBlocker(cap)
            health.status == CapabilityStatus.CRITICAL ->
                // Try alternative approach
                findAlternative(cap)
            health.status == CapabilityStatus.DEGRADED ->
                // Try to improve reliability
                improveReliability(cap)
            else -> null
        }

        if (recoveryPlan != null) {
            val result = acquisitionEngine.executePlan(
                OrionCapabilityIntelligence.AcquisitionPlan(
                    objective = "Health recovery for ${cap.name}",
                    steps = listOf(recoveryPlan),
                    totalEstimatedEffort = 1,
                    parallelizable = false
                )
            )
            if (result.overallSuccess) {
                OrionEvidenceLedger.recordDispatch(
                    svc, "health_monitor", "recovery_success", "recovery_success",
                    org.json.JSONObject()
                        .put("capability", cap.id)
                        .put("action", recoveryPlan.action.name)
                        .put("detail", result.stepResults.firstOrNull()?.detail ?: ""),
                    System.currentTimeMillis(),
                    "Recovery succeeded for ${cap.name}"
                )
            }
        }
    }

    private suspend fun resolveBlocker(cap: DiscoveredCapability): OrionCapabilityIntelligence.AcquisitionStep {
        return OrionCapabilityIntelligence.AcquisitionStep(
            capabilityId = cap.id,
            action = OrionCapabilityIntelligence.AcquisitionType.RESOLVE_BLOCKER,
            description = "Resolve blocker for ${cap.name}: ${cap.description}",
            estimatedEffort = OrionCapabilityIntelligence.EffortLevel.MEDIUM,
            prerequisites = emptyList()
        )
    }

    private suspend fun findAlternative(cap: DiscoveredCapability): OrionCapabilityIntelligence.AcquisitionStep {
        return OrionCapabilityIntelligence.AcquisitionStep(
            capabilityId = cap.id,
            action = OrionCapabilityIntelligence.AcquisitionType.ALTERNATIVE_STRATEGY,
            description = "Find alternative for ${cap.name}",
            estimatedEffort = OrionCapabilityIntelligence.EffortLevel.LOW,
            prerequisites = emptyList()
        )
    }

    private suspend fun improveReliability(cap: DiscoveredCapability): OrionCapabilityIntelligence.AcquisitionStep {
        return OrionCapabilityIntelligence.AcquisitionStep(
            capabilityId = cap.id,
            action = OrionCapabilityIntelligence.AcquisitionType.IMPROVE_RELIABILITY,
            description = "Improve reliability for ${cap.name}",
            estimatedEffort = OrionCapabilityIntelligence.EffortLevel.MEDIUM,
            prerequisites = emptyList()
        )
    }

    private fun toHealthStatus(cap: DiscoveredCapability, health: CapabilityHealth): CapabilityHealthStatus {
        return CapabilityHealthStatus(
            capabilityId = cap.id,
            status = health.status,
            successRate = health.successRate,
            totalCalls = health.totalCalls,
            lastError = health.lastError,
            lastErrorTime = health.lastErrorTime,
            blockReason = health.blockReason,
            lastAssessed = health.lastAssessed,
            lastRecoveryAttempt = healthStatus[cap.id]?.lastRecoveryAttempt ?: 0L
        )
    }

    // ========================================================================
    // DATA CLASSES
    // ========================================================================

    data class CapabilityHealthStatus(
        val capabilityId: String,
        val status: CapabilityStatus,
        val successRate: Double,
        val totalCalls: Long,
        val lastError: String?,
        val lastErrorTime: Long,
        val blockReason: String?,
        val lastAssessed: Long,
        val lastRecoveryAttempt: Long
    )
}
