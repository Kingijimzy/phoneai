package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orion Context Integration
 *
 * Integrates Cerebro memory, device state, available tools, mission history,
 * and current runtime state into a unified context for decision making.
 */
class OrionContextIntegration(
    private val svc: PhoneAiService,
    private val recall: OrionRecall,
    private val entityIndex: OrionEntityIndex,
    private val knowledgeGraph: OrionKnowledgeGraph,
    private val experienceStore: ExperienceStore,
    private val capabilityDiscovery: OrionCapabilityDiscovery,
    private val chronos: OrionChronosAwareness,
    private val proactiveEngine: OrionProactiveEngine,
    private val objectiveGraph: OrionObjectiveGraph,
    private val conversationRepository: OrionConversationRepository,
    private val evidenceLedger: OrionEvidenceLedger
) {

    data class UnifiedContext(
        val contextId: String,
        val timestamp: Long,
        val memory: MemoryContext,
        val device: DeviceContext,
        val capabilities: CapabilityContext,
        val missions: MissionContext,
        val runtime: RuntimeContext,
        val proactive: ProactiveContext,
        val session: SessionContext
    )

    data class MemoryContext(
        val recalledMemories: List<OrionRecall.MemoryItem>,
        val relevantEntities: List<OrionEntityIndex.EntityRecord>,
        val knowledgeFacts: List<OrionKnowledgeGraph.Fact>,
        val experiencePatterns: List<ExperienceStore.ExperiencePattern>,
        val recentFailures: List<OrionRecall.FailureRecord>,
        val recentSuccesses: List<OrionRecall.SuccessRecord>
    )

    data class DeviceContext(
        val rawSummary: String,
        val batteryLevel: Int?,
        val isCharging: Boolean,
        val networkType: String?,
        val isPowerSaveMode: Boolean,
        val screenState: ScreenState,
        val thermalState: String
    ) {
        enum class ScreenState { ON, OFF, UNKNOWN }
        
        companion object {
            fun fromSummary(summary: String): DeviceContext {
                var batteryLevel: Int? = null
                var isCharging = false
                var networkType: String? = null
                var isPowerSaveMode = false
                var screenState = ScreenState.UNKNOWN
                var thermalState = "unknown"

                // Parse: "OWNER-GRANTED DEVICE STATE: battery=85%; charging=true; power_saver=false; screen=interactive; network=wifi; thermal=normal"
                val batteryMatch = Regex("battery=(\\d+)%").find(summary)
                batteryMatch?.let { batteryLevel = it.groupValues[1].toIntOrNull() }
                
                val chargingMatch = Regex("charging=(true|false)").find(summary)
                chargingMatch?.let { isCharging = it.groupValues[1].toBoolean() }
                
                val networkMatch = Regex("network=(\\w+)").find(summary)
                networkMatch?.let { networkType = it.groupValues[1] }
                
                val powerSaveMatch = Regex("power_saver=(true|false)").find(summary)
                powerSaveMatch?.let { isPowerSaveMode = it.groupValues[1].toBoolean() }
                
                val screenMatch = Regex("screen=(\\w+)").find(summary)
                screenMatch?.let { 
                    screenState = when (it.groupValues[1]) {
                        "interactive" -> ScreenState.ON
                        "not_interactive" -> ScreenState.OFF
                        else -> ScreenState.UNKNOWN
                    }
                }
                
                val thermalMatch = Regex("thermal=(\\w+)").find(summary)
                thermalMatch?.let { thermalState = it.groupValues[1] }

                return DeviceContext(summary, batteryLevel, isCharging, networkType, isPowerSaveMode, screenState, thermalState)
            }
        }
    }

    data class CapabilityContext(
        val availableCapabilities: List<OrionCapabilityDiscovery.CapabilityInfo>,
        val enabledPacks: Set<String>,
        val blockedCapabilities: List<String>,
        val pendingCapabilities: List<String>,
        val capabilityHealth: Map<String, CapabilityHealth>
    ) {
        data class CapabilityHealth(
            val status: Status,
            val lastError: String?,
            val successRate: Double,
            val avgLatencyMs: Long
        ) {
            enum class Status { HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN }
        }
    }

    data class MissionContext(
        val activeObjectives: List<OrionObjectiveGraph.ActiveObjective>,
        val missionHistory: List<OrionObjectiveGraph.MissionRecord>,
        val currentPlan: OrionPlanningEngine.Plan?,
        val pendingCheckpoints: List<OrionTaskCheckpointStore.Checkpoint>,
        val activeDelegations: List<OrionDistributedExecution.DelegationRecord>
    )

    data class RuntimeContext(
        val sessionId: String,
        val interactionTraceId: String,
        val activeModality: String,
        val requestBudget: BudgetState,
        val skillState: SkillState?,
        val stopped: Boolean,
        val researchCancelled: Boolean
    ) {
        data class BudgetState(
            val used: Int,
            val remaining: Int,
            val max: Int
        )

        data class SkillState(
            val skillId: String,
            val startedAt: Long,
            val calls: Int
        )
    }

    data class ProactiveContext(
        val pendingNotifications: List<OrionProactiveEngine.ProactiveItem>,
        val overdueFollowUps: List<OrionFollowUpStore.FollowUp>,
        val upcomingCalendar: List<OrionCalendarMonitor.CalendarEvent>,
        val scheduledTasks: List<StandingOrders.StandingOrder>
    )

    data class SessionContext(
        val conversationHistory: List<Pair<String, String>>,
        val activeObjective: String?,
        val userPreferences: Map<String, String>,
        val activeSkills: List<String>
    )

    /** Lightweight active objective info for context integration. */
    data class ActiveObjectiveInfo(
        val id: String,
        val objective: String,
        val status: String,
        val dependencies: List<String>
    )

    /** Lightweight mission record info for context integration. */
    data class MissionRecordInfo(
        val objectiveId: String,
        val objective: String,
        val status: MissionStatusInfo
    ) {
        enum class MissionStatusInfo { PENDING, READY, RUNNING, BLOCKED, COMPLETE, FAILED }
    }

    /**
     * Build unified context for decision making.
     */
    fun buildContext(
        input: String,
        modality: String,
        activeObjective: String?,
        sessionData: SessionData
    ): UnifiedContext {
        val contextId = "ctx_${System.currentTimeMillis()}_${Math.abs(input.hashCode())}"

        // 1. Memory context - recall relevant information
        val memory = buildMemoryContext(input, activeObjective, sessionData.conversationHistory)

        // 2. Device context - current device state
        val device = buildDeviceContext()

        // 3. Capability context - available tools and their health
        val capabilities = buildCapabilityContext()

        // 4. Mission context - active objectives and history
        val missions = buildMissionContext(activeObjective)

        // 5. Runtime context - current execution state
        val runtime = buildRuntimeContext(sessionData)

        // 6. Proactive context - upcoming events and notifications
        val proactive = buildProactiveContext()

        // 7. Session context - conversation and preferences
        val session = buildSessionContext(sessionData, activeObjective)

        return UnifiedContext(
            contextId = contextId,
            timestamp = System.currentTimeMillis(),
            memory = memory,
            device = device,
            capabilities = capabilities,
            missions = missions,
            runtime = runtime,
            proactive = proactive,
            session = session
        )
    }

    private fun buildMemoryContext(
        input: String,
        activeObjective: String?,
        history: List<Pair<String, String>>
    ): MemoryContext {
        // Recall relevant memories
        val query = if (activeObjective != null && activeObjective.isNotBlank()) {
            "$input (context: $activeObjective)"
        } else input

        val recalledMemories = recall.recall(query, maxResults = 8)

        // Get relevant entities
        val entities = entityIndex.getRecentEntities(history, limit = 15)

        // Get knowledge facts
        val facts = knowledgeGraph.queryFacts(input, maxResults = 10)

        // Get experience patterns
        val patterns = experienceStore.getRelevantPatterns(input, limit = 5)

        // Recent failures and successes
        val failures = recall.getRecentFailures(limit = 5)
        val successes = recall.getRecentSuccesses(limit = 5)

        return MemoryContext(
            recalledMemories = recalledMemories,
            relevantEntities = entities,
            knowledgeFacts = facts,
            experiencePatterns = patterns,
            recentFailures = failures,
            recentSuccesses = successes
        )
    }

    private fun buildDeviceContext(): DeviceContext {
        val deviceSummary = OrionDeviceContextProvider.summary(svc)
        return DeviceContext.fromSummary(deviceSummary)
    }

    private fun buildCapabilityContext(): CapabilityContext {
        val available = capabilityDiscovery.getAvailableCapabilities(svc)
        val enabledPacks = AgentPolicy.getEnabledPacks(svc)
        val blocked = capabilityDiscovery.getBlockedCapabilities(svc)
        val pending = capabilityDiscovery.getPendingCapabilities(svc)

        val health = mutableMapOf<String, CapabilityContext.CapabilityHealth>()
        for (cap in available) {
            val successRate = OrionRecall.getCapabilitySuccessRate(svc, cap.name)
            val avgLatency = OrionRecall.getCapabilityAvgLatency(svc, cap.name)
            val lastError = OrionRecall.getCapabilityLastError(svc, cap.name)

            health[cap.name] = CapabilityContext.CapabilityHealth(
                status = when {
                    successRate < 0.5 -> CapabilityContext.CapabilityHealth.Status.UNHEALTHY
                    successRate < 0.8 -> CapabilityContext.CapabilityHealth.Status.DEGRADED
                    else -> CapabilityContext.CapabilityHealth.Status.HEALTHY
                },
                lastError = lastError,
                successRate = successRate,
                avgLatencyMs = avgLatency
            )
        }

        return CapabilityContext(
            availableCapabilities = available,
            enabledPacks = enabledPacks,
            blockedCapabilities = blocked,
            pendingCapabilities = pending,
            capabilityHealth = health
        )
    }

    private fun buildMissionContext(activeObjective: String?): MissionContext {
        val activeGraph = objectiveGraph.load(svc)
        val activeObjectives = activeGraph?.nodes?.filter { it.status == OrionObjectiveGraph.Status.IN_PROGRESS }
            ?.map { obj ->
                OrionContextIntegration.ActiveObjectiveInfo(obj.id, obj.objective, obj.status.name, obj.dependencies)
            } ?: emptyList()

        val missionHistory = activeGraph?.nodes?.map { OrionContextIntegration.MissionRecordInfo(it.id, it.objective, OrionContextIntegration.MissionStatusInfo.valueOf(it.status.name)) } ?: emptyList()

        val currentPlan = activeGraph?.let { graph ->
            OrionPlanningEngine.Plan(
                planId = graph.id,
                objectiveId = graph.id,
                nodes = graph.nodes.map { node ->
                    OrionPlanningEngine.PlanNode(
                        id = node.id,
                        objective = node.objective,
                        tool = node.tool,
                        argsTemplate = try { JSONObject(node.argsTemplate) } catch (_: Exception) { JSONObject() },
                        capability = node.tool,
                        verificationMethod = OrionIntentIntelligence.SuccessCriterion.VerificationMethod.valueOf(node.verificationMethod),
                        expectedEvidence = "",
                        status = OrionPlanningEngine.PlanNode.NodeStatus.valueOf(node.status.name),
                        dependencies = node.dependencies,
                        timeoutMs = node.timeoutMs,
                        retryPolicy = OrionPlanningEngine.RetryPolicy(2, 2000, setOf("timeout", "verification_failed"), null),
                        isOptional = false
                    )
                },
                edges = graph.edges.map { OrionPlanningEngine.PlanEdge(it.from, it.to, OrionPlanningEngine.PlanEdge.EdgeType.valueOf(it.type)) },
                globalConstraints = graph.globalConstraints.map { gc ->
                    OrionPlanningEngine.GlobalConstraint(
                        OrionPlanningEngine.GlobalConstraint.ConstraintType.valueOf(gc.type),
                        gc.description,
                        OrionPlanningEngine.GlobalConstraint.Severity.valueOf(gc.severity)
                    )
                },
                successConditions = emptyList(),
                estimatedDurationMs = graph.nodes.sumOf { it.timeoutMs },
                riskLevel = OrionPlanningEngine.Plan.RiskLevel.MEDIUM,
                metadata = OrionPlanningEngine.PlanMetadata()
            )
        }

        val pendingCheckpoints = OrionTaskCheckpointStore.getPendingCheckpoints(svc)
        val activeDelegations = OrionDistributedExecution.getActiveDelegations(svc)

        return MissionContext(
            activeObjectives = activeObjectives,
            missionHistory = missionHistory,
            currentPlan = currentPlan,
            pendingCheckpoints = pendingCheckpoints,
            activeDelegations = activeDelegations
        )
    }

    private fun buildRuntimeContext(sessionData: SessionData): RuntimeContext {
        return RuntimeContext(
            sessionId = sessionData.sessionId,
            interactionTraceId = sessionData.interactionTraceId,
            activeModality = sessionData.modality,
            requestBudget = RuntimeContext.BudgetState(
                used = sessionData.budgetUsed,
                remaining = sessionData.budgetRemaining,
                max = sessionData.budgetMax
            ),
            skillState = sessionData.activeSkillId?.let { skillId ->
                RuntimeContext.SkillState(
                    skillId = skillId,
                    startedAt = sessionData.activeSkillStartedAt,
                    calls = sessionData.activeSkillCalls
                )
            },
            stopped = sessionData.stopped,
            researchCancelled = sessionData.researchCancelled
        )
    }

    private fun buildProactiveContext(): ProactiveContext {
        val pendingNotifications = proactiveEngine.getPendingNotifications(svc)
        val overdueFollowUps = OrionFollowUpStore.getOverdue(svc)
        val upcomingCalendar = OrionCalendarMonitor.getUpcoming(svc, hours = 24)
        val scheduledTasks = StandingOrders.getActiveOrders(svc)

        return ProactiveContext(
            pendingNotifications = pendingNotifications,
            overdueFollowUps = overdueFollowUps,
            upcomingCalendar = upcomingCalendar,
            scheduledTasks = scheduledTasks
        )
    }

    private fun buildSessionContext(
        sessionData: SessionData,
        activeObjective: String?
    ): SessionContext {
        return SessionContext(
            conversationHistory = sessionData.conversationHistory,
            activeObjective = activeObjective,
            userPreferences = sessionData.userPreferences,
            activeSkills = sessionData.activeSkills
        )
    }

    /**
     * Get context summary for decision rationale.
     */
    fun getContextSummary(context: UnifiedContext): String {
        return "Memory: ${context.memory.recalledMemories.size} recalled, ${context.memory.relevantEntities.size} entities | " +
            "Device: ${context.device.batteryLevel ?: "?"}% battery, ${context.device.networkType ?: "?"} net | " +
            "Capabilities: ${context.capabilities.availableCapabilities.size} available, ${context.capabilities.blockedCapabilities.size} blocked | " +
            "Missions: ${context.missions.activeObjectives.size} active, ${context.missions.missionHistory.size} history | " +
            "Proactive: ${context.proactive.pendingNotifications.size} pending, ${context.proactive.overdueFollowUps.size} overdue | " +
            "Runtime: ${context.runtime.activeModality}, budget ${context.runtime.requestBudget.remaining}/${context.runtime.requestBudget.max}"
    }

    /**
     * Get condensed context for model prompt.
     */
    fun getCondensedContextForPrompt(context: UnifiedContext, maxChars: Int = 2000): String {
        val builder = StringBuilder()
        builder.append("=== CONTEXT ===\n")

        // Key memories
        if (context.memory.recalledMemories.isNotEmpty()) {
            builder.append("Relevant memories:\n")
            for (mem in context.memory.recalledMemories.take(3)) {
                builder.append("  - ${mem.content.take(200)}\n")
            }
        }

        // Active objectives
        if (context.missions.activeObjectives.isNotEmpty()) {
            builder.append("Active objectives:\n")
            for (obj in context.missions.activeObjectives.take(3)) {
                builder.append("  - ${obj.objective} (${obj.status})\n")
            }
        }

        // Device state
        builder.append("Device: ${context.device.batteryLevel ?: "?"}% battery, ${context.device.screenState}, ${context.device.networkType ?: "?"} net\n")

        // Capabilities
        val keyCapabilities = context.capabilities.availableCapabilities
            .filter { it.category in setOf("PHONE", "WEB", "PYTHON", "TRADING") }
            .take(10)
            .map { it.name }
        builder.append("Key capabilities: ${keyCapabilities.joinToString(", ")}\n")

        // Proactive
        if (context.proactive.upcomingCalendar.isNotEmpty()) {
            builder.append("Upcoming: ${context.proactive.upcomingCalendar.take(2).joinToString("; ") { it.title @ ${it.time} }}\n")
        }

        return builder.toString().take(maxChars)
    }
}

/**
 * Session data passed from Agent/UnifiedRuntime.
 */
data class SessionData(
    val sessionId: String,
    val interactionTraceId: String,
    val modality: String,
    val conversationHistory: List<Pair<String, String>>,
    val activeObjective: String?,
    val userPreferences: Map<String, String>,
    val activeSkills: List<String>,
    val activeSkillId: String? = null,
    val activeSkillStartedAt: Long = 0L,
    val activeSkillCalls: Int = 0,
    val budgetUsed: Int = 0,
    val budgetRemaining: Int = 15,
    val budgetMax: Int = 15,
    val stopped: Boolean = false,
    val researchCancelled: Boolean = false
)
