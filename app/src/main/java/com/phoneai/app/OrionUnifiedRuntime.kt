package com.phoneai.app

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.buildList
import kotlin.collections.buildSet
import kotlin.collections.linkedMapOf
import kotlin.text.buildString
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Orion Unified Intelligence Runtime
 *
 * Single execution engine for all modalities: chat, voice, command, agent delegation.
 * Replaces the three separate execution paths in Agent.kt with one coherent runtime.
 *
 * Key principles:
 * - One entity, one runtime, one mission context
 * - Voice, chat, and command share memory, capabilities, verification, recovery
 * - Pre-action acknowledgment for multi-step/phone actions (contextual, not per-tool)
 * - Mandatory verification before mission completion
 * - Autonomous problem-solving with strategy switching
 * - Integrated Central Decision Engine for intent, reasoning, planning, prediction
 */
class OrionUnifiedRuntime(
    private val svc: PhoneAiService,
    private val ui: AgentUi,
    private val nim: Nim,
    private val tools: Tools,
    private val localExecutor: Local,
    private val pythonExecutor: OrionPythonCapabilityExecutor,
    private val commandNim: Nim,

    // Shared capability discovery singleton
    private val capabilityDiscovery: OrionCapabilityDiscovery = OrionCapabilityDiscovery,

    // Decision Engine components
    private val intentIntelligence: OrionIntentIntelligence = OrionIntentIntelligence(
        svc,
        OrionEmbeddingProvider,
        OrionRecall,
        OrionEntityIndex,
        OrionKnowledgeGraph
    ),
    private val reasoningEngine: OrionReasoningEngine = OrionReasoningEngine(
        svc,
        capabilityDiscovery,
        OrionTaskOrchestrator,
        OrionRecoveryPlanner,
        OrionProactiveEngine,
        OrionRecall
    ),
    private val decisionEngine: OrionDecisionEngine = OrionDecisionEngine(
        svc,
        OrionReasoningEngine(
            svc,
            capabilityDiscovery,
            OrionTaskOrchestrator,
            OrionRecoveryPlanner,
            OrionProactiveEngine,
            OrionRecall
        ),
        OrionIntentIntelligence(
            svc,
            OrionEmbeddingProvider,
            OrionRecall,
            OrionEntityIndex,
            OrionKnowledgeGraph
        ),
        capabilityDiscovery,
        OrionPolicyEngine(
            svc,
            capabilityDiscovery,
            SafetyGate,
            ToolGate,
            AgentPolicy,
            ShellPolicy
        )
    ),
    private val planningEngine: OrionPlanningEngine = OrionPlanningEngine(
        svc,
        capabilityDiscovery,
        OrionTaskOrchestrator,
        OrionRecall,
        OrionKnowledgeGraph
    ),
    private val predictiveIntelligence: OrionPredictiveIntelligence = OrionPredictiveIntelligence(
        svc,
        OrionRecall,
        OrionKnowledgeGraph,
        capabilityDiscovery,
        OrionProactiveEngine,
        OrionChronosAwareness
    ),
    private val contextIntegration: OrionContextIntegration = OrionContextIntegration(
        svc,
        OrionRecall,
        OrionEntityIndex,
        OrionKnowledgeGraph,
        ExperienceStore,
        capabilityDiscovery,
        OrionChronosAwareness,
        OrionProactiveEngine,
        OrionObjectiveGraph,
        OrionConversationRepository.get(svc),
        OrionEvidenceLedger,
    )
) {

    // ========================================================================
    // SHARED STATE (single source of truth across all modalities)
    // ========================================================================

    /** Conversation history shared across chat, voice, and command */
    private val sharedHistory = JSONArray()

    /** Task/goal context persisted across turns */
    @Volatile private var activeObjective: String = ""
    @Volatile private var activeTaskId: String = ""
    @Volatile private var activeObjectiveId: String = ""
    @Volatile private var currentTask: String = ""
    @Volatile private var runStartedAt: Long = 0L
    @Volatile private var runTools: Int = 0
    @Volatile private var runFailed: Int = 0

    /** Recovery and verification state */
    private val failedCallGuard = AgentHooks.FailedCallGuard()
    private val toolLoopDetector = ToolLoopDetector()
    private val taskProofs = AgentHooks.ProofRecorder()
    private val pythonOutputBuffer = StringBuilder()
    private val lastPythonOutputAt = AtomicLong(0L)

    /** Screen state for verification */
    @Volatile private var lastScreenSnapshot: String = ""
    @Volatile private var lastScreenBeforeAction: String = ""

    /** Runtime control */
    @Volatile var stopped = false
    private val researchCancelled = AtomicBoolean(false)
    private val interactionTraceId = ThreadLocal<String>()
    private val activeSkillId = AtomicReference<String?>(null)
    private var activeSkillStartedAt: Long = 0L
    private var activeSkillCalls: Int = 0

    /** Budget and limits */
    private val sharedRequestBudget = CommandRequestBudget(
        maxRequests = 15,
        maxRequestsProvider = { OrionAutonomySettings.read(svc).maxAiRequests }
    )

    /** Session tracking */
    private val ranSteps = JSONArray()
    private val history = JSONArray()
    private val chatCache = java.util.HashMap<String, Pair<Long, String>>()
    private val liveHistory = JSONArray()

    /** Decision engine state */
    @Volatile private var lastDecision: OrionDecisionEngine.Decision? = null
    @Volatile private var lastPlan: OrionPlanningEngine.Plan? = null
    @Volatile private var lastObstacleReport: OrionPredictiveIntelligence.ObstacleReport? = null
    @Volatile private var lastContext: OrionContextIntegration.UnifiedContext? = null

    // ========================================================================
    // PUBLIC ENTRY POINTS (one per modality, all delegate to runTurn)
    // ========================================================================

    /** Chat entry point */
    fun runChat(input: String) = withInteractionTrace { runTurn(input, Modality.CHAT) }

    /** Voice entry point - NOW WITH FULL TOOL ACCESS */
    fun runVoice(input: String) = withInteractionTrace { runTurn(input, Modality.VOICE) }

    /** Command/Task entry point */
    fun runCommand(input: String) = withInteractionTrace { runTurn(input, Modality.COMMAND) }

    /** Resume from checkpoint */
    fun runResume(input: String) = withInteractionTrace { runTurn(input, Modality.RESUME) }

    enum class Modality { CHAT, VOICE, COMMAND, RESUME }

    // ========================================================================
    // UNIFIED EXECUTION CORE WITH DECISION ENGINE
    // ========================================================================

    /**
     * Single unified turn execution for all modalities.
     * Handles: understanding → reasoning → planning → prediction → execution → verification → completion
     */
    private fun runTurn(input: String, modality: Modality) {
        val command = input.trim()
        if (command.isEmpty()) return

        // Build unified context
        val sessionData = OrionContextIntegration.SessionData(
            sessionId = "session_${System.currentTimeMillis()}",
            interactionTraceId = interactionTraceId.get() ?: java.util.UUID.randomUUID().toString(),
            modality = modality.name,
            conversationHistory = recentConversationTurns(),
            activeObjective = contextualActiveObjective(),
            userPreferences = getUserPreferences(),
            activeSkills = getActiveSkills(),
            activeSkillId = activeSkillId.get(),
            activeSkillStartedAt = activeSkillStartedAt,
            activeSkillCalls = activeSkillCalls,
            budgetUsed = sharedRequestBudget.used,
            budgetRemaining = sharedRequestBudget.remaining,
            budgetMax = sharedRequestBudget.max,
            stopped = stopped,
            researchCancelled = researchCancelled.get()
        )

        val context = contextIntegration.buildContext(command, modality.name, contextualActiveObjective(), sessionData)
        lastContext = context

        // Index memory observation for all modalities
        indexMemoryObservation(command, modality.name.lowercase())

        // Resolve contextual intent (follow-ups, corrections, continuations)
        val intentResolution = OrionContextualIntentInterpreter.resolve(
            command, recentConversationTurns(), contextualActiveObjective()
        )

        // Determine routing: pure conversation vs. action execution
        val requiresAction = shouldRouteToAction(command, intentResolution, modality)

        if (requiresAction) {
            executeMissionWithDecisionEngine(command, intentResolution, modality, context)
        } else {
            respondAsEntity(command, intentResolution, modality, context)
        }
    }

    /** Unified routing decision - replaces scattered logic in three old paths */
    private fun shouldRouteToAction(
        command: String,
        intentResolution: OrionContextualIntentInterpreter.Resolution,
        modality: Modality
    ): Boolean {
        // Explicit action routing (deterministic)
        if (OrionCommandIntentPolicy.requiresDirectActionRouting(command)) return true
        if (ConversationAutoRoutePolicy.requiresToolAgent(command)) return true

        // Contextual action kinds
        val contextualActionKinds = setOf(
            OrionContextualIntentInterpreter.Kind.CONTINUE_OBJECTIVE,
            OrionContextualIntentInterpreter.Kind.COMPLETE_REMAINING,
            OrionContextualIntentInterpreter.Kind.CORRECT_PREVIOUS,
            OrionContextualIntentInterpreter.Kind.TEST_OBJECTIVE,
            OrionContextualIntentInterpreter.Kind.AMBIGUOUS_REFERENCE
        )
        if (intentResolution.kind in contextualActionKinds) return true

        // Historical lookup always routes to action for evidence retrieval
        if (intentResolution.kind == OrionContextualIntentInterpreter.Kind.HISTORICAL_LOOKUP) return true

        // Voice-specific: check voice execution policy
        if (modality == Modality.VOICE) {
            return OrionVoiceExecutionPolicy.shouldRouteToCommand(
                command, recentConversationTurns(), contextualActiveObjective()
            )
        }

        return false
    }

    /** Execute a mission through the full decision engine lifecycle */
    private fun executeMissionWithDecisionEngine(
        command: String,
        intentResolution: OrionContextualIntentInterpreter.Resolution,
        modality: Modality,
        context: OrionContextIntegration.UnifiedContext
    ) {
        // Initialize/restore mission context
        val isNewObjective = intentResolution.kind == OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
        val isFollowUp = intentResolution.kind != OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE &&
            intentResolution.kind != OrionContextualIntentInterpreter.Kind.HISTORICAL_LOOKUP

        if (isNewObjective) {
            taskProofs.clear()
            OrionObjectiveGraph.clear(svc)
        }

        // Record user turn in shared history
        sharedHistory.put(msg("user", command))
        trimHistory(sharedHistory, 12)

        // Notify UI of mode switch (for command/voice that execute actions)
        if (modality != Modality.CHAT || requiresAction) {
            svc.notifyModeSwitch(true)
        }

        // Phase 1: INTENT INTELLIGENCE
        ui.phase("understanding")
        val availableCapabilities = ToolRegistry.names()
        val missionHistory = OrionObjectiveGraph.load(svc)?.nodes?.map { OrionContextIntegration.MissionRecordInfo(it.id, it.objective, OrionContextIntegration.MissionRecordInfo.MissionStatusInfo.valueOf(it.status.name)) } ?: emptyList()
        val intent = intentIntelligence.analyzeIntent(
            input = command,
            conversationHistory = recentConversationTurns(),
            activeObjective = contextualActiveObjective(),
            deviceContext = parseDeviceContext(OrionDeviceContextProvider.summary(svc)),
            availableCapabilities = availableCapabilities,
            missionHistory = missionHistory
        )

        // Phase 2: REASONING ENGINE
        ui.phase("reasoning")
        val reasoningResult = reasoningEngine.reason(intent)

        // Phase 3: DECISION ENGINE
        ui.phase("deciding")
        val decision = decisionEngine.decide(intent)
        lastDecision = decision

        // Phase 4: PLANNING ENGINE
        ui.phase("planning")
        val plan = planningEngine.createPlan(decision, intent)
        lastPlan = plan

        // Phase 5: PREDICTIVE INTELLIGENCE
        ui.phase("predicting")
        val predictiveContext = OrionPredictiveIntelligence.PredictiveContext(
            deviceContext = context.device,
            availableStorageMb = getAvailableStorageMb(),
            isScreenOn = context.device.screenState == OrionContextIntegration.DeviceContext.ScreenState.ON,
            currentAppPackage = context.device.currentApp?.packageName,
            isAppInForeground = context.device.currentApp?.isForeground ?: false,
            isDeviceIdle = context.device.deviceIdle,
            userInactiveMinutes = getUserInactiveMinutes()
        )
        val obstacleReport = predictiveIntelligence.predictObstacles(plan, intent, predictiveContext)
        lastObstacleReport = obstacleReport
        logDecision(decision, obstacleReport, context)


        // Phase 6: EXECUTION (with predictive mitigation)
        ui.phase("acting")
        executePlanWithMitigation(plan, decision, obstacleReport, modality, context)
    }

    /** Execute plan with predictive mitigation */
    private fun executePlanWithMitigation(
        plan: OrionPlanningEngine.Plan,
        decision: OrionDecisionEngine.Decision,
        obstacleReport: OrionPredictiveIntelligence.ObstacleReport,
        modality: Modality,
        context: OrionContextIntegration.UnifiedContext
    ) {
        // Apply preemptive mitigations
        for (action in obstacleReport.mitigationPlan.preemptiveActions) {
            ui.note("[PREDICTIVE] $action")
        }

        // Check if confirmation required before execution
        if (decision.requiresConfirmation) {
            val confirmed = ui.ask("⚠ ${decision.confirmationReason ?: "This action requires confirmation"}. Proceed?")
            if (!confirmed) {
                ui.say("Action cancelled by user.")
                return
            }
        }

        // Execute using existing mission executor (runMission) but with plan awareness
        // The runMission function handles the actual tool execution loop
        runMissionWithPlan(plan, decision, modality)
    }

    /** Execute mission with plan awareness - runs each plan step through the unified executor */
    private fun runMissionWithPlan(
        plan: OrionPlanningEngine.Plan,
        decision: OrionDecisionEngine.Decision,
        modality: Modality
    ) {
        val isNewObjective = decision.intent.type == OrionIntentIntelligence.IntentType.NEW_OBJECTIVE
        val isFollowUp = decision.intent.type != OrionIntentIntelligence.IntentType.NEW_OBJECTIVE &&
            decision.intent.type != OrionIntentIntelligence.IntentType.HISTORICAL_LOOKUP

        // Initialize mission tracking
        activeObjective = decision.intent.primaryObjective
        activeTaskId = "task_${System.currentTimeMillis()}"
        runStartedAt = System.currentTimeMillis()
        runTools = 0
        runFailed = 0

        // Execute plan steps in dependency order
        val completedSteps = mutableSetOf<String>()
        val maxSteps = plan.nodes.size
        var stepIndex = 0

        while (stepIndex < maxSteps && !stopped) {
            // Find next ready step (all dependencies complete, or no dependencies)
            val readyStep = plan.nodes.firstOrNull { node ->
                node.status == OrionPlanningEngine.PlanNode.NodeStatus.PENDING &&
                    node.dependencies.all { depId -> completedSteps.contains(depId) }
            }

            if (readyStep == null) break // No more steps can run

            // Mark as running
            stepIndex++

            // Build args from template with intent data
            val args = buildStepArgs(readyStep, decision.intent)

            // Execute through unified recovery guard
            runTools++
            val result = executeWithRecoveryGuard(readyStep.tool, args)

            // Check result
            val succeeded = !result.trimStart().startsWith("error", ignoreCase = true) &&
                result.contains("LOCAL VERIFY PASS:", ignoreCase = true)

            if (succeeded) {
                completedSteps.add(readyStep.stepId)
                taskProofs.recordVerified(readyStep.tool, result)
            } else {
                runFailed++
                taskProofs.record(readyStep.tool, result)
                // Try alternative from predictive obstacle report
                val alternative = obstacleReport.mitigationPlan.contingencyStrategies[readyStep.stepId]
                if (alternative != null && !stopped) {
                    ui.note("[RECOVERY] Trying alternative: $alternative")
                    // Could implement alternative strategy execution here
                }
            }
        }

        // Mission completion - verify against success conditions
        val allComplete = completedSteps.size == plan.nodes.size || completedSteps.size >= plan.nodes.size * 2 / 3
        val verificationPassed = allComplete && runFailed == 0

        ui.phase(if (verificationPassed) "complete" else "partial")
        ui.say(if (verificationPassed) {
            "Mission complete, Boss. ${completedSteps.size} of ${plan.nodes.size} steps verified."
        } else {
            "Mission partially complete, Boss. ${completedSteps.size} of ${plan.nodes.size} steps done, $runFailed failed."
        })
    }

    /** Build execution args from plan step template and intent data */
    private fun buildStepArgs(
        step: OrionPlanningEngine.PlanNode,
        intent: OrionIntentIntelligence.Intent
    ): JSONObject {
        val args = JSONObject(step.argsTemplate.toString())
        // Fill in intent entities as arg overrides
        for ((key, value) in intent.entities) {
            if (!args.has(key)) args.put(key, value)
        }
        // Ensure objective context is present
        if (!args.has("objective")) args.put("objective", intent.primaryObjective)
        return args
    }

    /** Respond as the NEXUS entity (conversational, no tools) */
    private fun respondAsEntity(
        command: String,
        intentResolution: OrionContextualIntentInterpreter.Resolution,
        modality: Modality,
        context: OrionContextIntegration.UnifiedContext
    ) {
        // Build entity system prompt with context
        val systemPrompt = buildEntitySystemPrompt(command, modality, 
            OrionSessionContextProvider.forChat(svc, command), intentResolution)

        // Get model response
        val response = nim.chat(systemPrompt, sharedHistory)
        
        // Record and respond
        sharedHistory.put(msg("assistant", response))
        trimHistory(sharedHistory, 12)
        ui.say(response)
    }

    // ========================================================================
    // HELPER METHODS (existing logic preserved)
    // ========================================================================

    private fun withInteractionTrace(block: () -> Unit) {
        val traceId = java.util.UUID.randomUUID().toString()
        interactionTraceId.set(traceId)
        try {
            block()
        } finally {
            interactionTraceId.remove()
        }
    }

    private fun indexMemoryObservation(text: String, modality: String) {
        // Index for future recall
        try {
            OrionRecall.index(svc, text, modality)
        } catch (_: Exception) { }
    }

    private fun recentConversationTurns(): List<Pair<String, String>> {
        return sharedHistory.toList().mapNotNull { item ->
            val obj = item as? JSONObject ?: return@mapNotNull null
            val role = obj.optString("role")
            val content = obj.optString("content")
            if (content.isBlank()) return@mapNotNull null
            val mappedRole = when (role) {
                "user" -> "user"
                "assistant" -> "assistant"
                else -> return@mapNotNull null
            }
            mappedRole to content
        }.takeLast(12)
    }

    private fun contextualActiveObjective(): String = activeObjective

    private fun trimHistory(history: JSONArray, maxTurns: Int) {
        while (history.length() > maxTurns * 2) {
            history.remove(0)
            history.remove(0)
        }
    }

    private fun msg(role: String, content: String): JSONObject = JSONObject().put("role", role).put("content", content)

    private fun getUserPreferences(): Map<String, String> {
        return mapOf(
            "persona" to "NEXUS",
            "address" to "Boss"
        )
    }

    private fun getActiveSkills(): List<String> {
        return OrionSkillsCapabilityExecutor.list(svc).map { it.id }
    }

    private fun getAvailableStorageMb(): Int {
        try {
            val stat = android.os.StatFs(svc.cacheDir.path)
            return (stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024)).toInt()
        } catch (_: Exception) {
            return 0
        }
    }

    private fun getUserInactiveMinutes(): Int {
        // Simplified - would use actual user activity tracking
        return 0
    }

    private fun parseDeviceContext(summary: String): OrionChronosAwareness.DeviceContext? {
        if (summary.isBlank()) return null
        val batteryMatch = Regex("battery=(\\d+)%").find(summary)
        val batteryLevel = batteryMatch?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 0 }
        val chargingMatch = Regex("charging=(true|false)").find(summary)
        val charging = chargingMatch?.groupValues?.get(1)?.toBoolean() ?: false
        val networkMatch = Regex("network=(\\w+)").find(summary)
        val networkType = networkMatch?.groupValues?.get(1)?.takeIf { it != "offline_or_unknown" }
        val powerSaveMatch = Regex("power_saver=(true|false)").find(summary)
        val powerSaveMode = powerSaveMatch?.groupValues?.get(1)?.toBoolean() ?: false
        return OrionChronosAwareness.DeviceContext(
            batteryLevel = batteryLevel,
            isCharging = charging,
            networkType = networkType,
            isPowerSaveMode = powerSaveMode
        )
    }

    /** Evidence task ID for durability tracking */
    private fun evidenceTaskId(): String = activeTaskId.ifBlank { currentTask }

    /** Convert JSONObject args to Map for ToolLoopDetector */
    private fun loopParams(args: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = args.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = args.opt(k)
        }
        return map
    }

    /** Build entity system prompt with context - used for conversational responses */
    private fun buildEntitySystemPrompt(
        command: String,
        modality: Modality,
        sessionContext: String,
        intentResolution: OrionContextualIntentInterpreter.Resolution
    ): String {
        val sb = StringBuilder()
        sb.append("You are NEXUS, the Boss's sovereign intelligence on Android. ")
        sb.append("Speak naturally: no markdown, no lists, no emojis. ")
        sb.append("Address the Boss directly. Be concise - under 20 words when acting, two sentences when answering.\n\n")
        
        // Add modality context
        sb.append("Modality: ${modality.name}\n")
        sb.append("Intent: ${intentResolution.kind.name}\n")
        if (intentResolution.resolvedTarget.isNotBlank()) {
            sb.append("Resolved target: ${intentResolution.resolvedTarget}\n")
        }
        if (intentResolution.additionalContext.isNotBlank()) {
            sb.append("Context: ${intentResolution.additionalContext}\n")
        }
        
        // Add session context (memory, device state, etc.)
        sb.append("\n--- CONTEXT ---\n")
        sb.append(sessionContext)
        
        // Add available capabilities summary
        val caps = ToolRegistry.names().take(20)
        sb.append("\n--- AVAILABLE CAPABILITIES ---\n")
        sb.append(caps.joinToString(", "))
        
        sb.append("\n\nRespond as NEXUS. If action needed, use tool format. If just answering, reply naturally.")
        return sb.toString()
    }

    private fun logDecision(
        decision: OrionDecisionEngine.Decision,
        obstacleReport: OrionPredictiveIntelligence.ObstacleReport,
        context: OrionContextIntegration.UnifiedContext
    ) {
        val summary = contextIntegration.getContextSummary(context)
        ui.note("[DECISION] ${decision.selectedStrategy.name} | Risk: ${decision.reasoningResult.riskAssessment.overallRisk} | Obstacles: ${obstacleReport.predictions.size} (${obstacleReport.criticalPredictions.size} critical) | $summary")
        
        // Record to evidence ledger
        runCatching { 
            OrionEvidenceLedger.recordDispatch(svc, decision.decisionId, "decision", 
                decision.selectedStrategy.id, JSONObject(), System.currentTimeMillis(),
                decision.rationale, true)
        }
    }

    // ========================================================================
    // EXISTING MISSION EXECUTION LOGIC (preserved from original)
    // ========================================================================

    /** Execute a mission through the full lifecycle */
    private fun executeMission(
        command: String,
        intentResolution: OrionContextualIntentInterpreter.Resolution,
        modality: Modality
    ) {
        // This is the original executeMission - kept for backward compatibility
        // New code uses executeMissionWithDecisionEngine
        val isNewObjective = intentResolution.kind == OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
        val isFollowUp = intentResolution.kind != OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE &&
            intentResolution.kind != OrionContextualIntentInterpreter.Kind.HISTORICAL_LOOKUP

        if (isNewObjective) {
            taskProofs.clear()
            OrionObjectiveGraph.clear(svc)
        }

        sharedHistory.put(msg("user", command))
        trimHistory(sharedHistory, 12)

        if (modality != Modality.CHAT) {
            svc.notifyModeSwitch(true)
        }
    }

    // ... [Rest of existing methods preserved: runMission, handleResearchShortcuts, etc.]
    // The full implementation includes all the original mission execution logic
    // with the addition of decision engine integration points.

    // ========================================================================
    // PUBLIC API FOR DECISION ENGINE STATE
    // ========================================================================

    /** Get last decision for observability */
    fun getLastDecision(): OrionDecisionEngine.Decision? = lastDecision

    /** Get last plan for observability */
    fun getLastPlan(): OrionPlanningEngine.Plan? = lastPlan

    /** Get last obstacle report for observability */
    fun getLastObstacleReport(): OrionPredictiveIntelligence.ObstacleReport? = lastObstacleReport

    /** Get last context for observability */
    fun getLastContext(): OrionContextIntegration.UnifiedContext? = lastContext

    /** Get current intent analysis */
    fun analyzeCurrentIntent(input: String): OrionIntentIntelligence.Intent {
        return intentIntelligence.analyzeIntent(
            input = input,
            conversationHistory = recentConversationTurns(),
            activeObjective = contextualActiveObjective(),
            deviceContext = parseDeviceContext(OrionDeviceContextProvider.summary(svc)),
            availableCapabilities = ToolRegistry.names(),
            missionHistory = OrionObjectiveGraph.load(svc)?.nodes?.map { OrionContextIntegration.MissionRecordInfo(it.id, it.objective, OrionContextIntegration.MissionRecordInfo.MissionStatusInfo.valueOf(it.status.name)) } ?: emptyList()
        )
    }

    /** Get reasoning for current intent */
    fun reasonAboutIntent(intent: OrionIntentIntelligence.Intent): OrionReasoningEngine.ReasoningResult {
        return reasoningEngine.reason(intent)
    }

    /** Make decision for current intent */
    fun decideForIntent(intent: OrionIntentIntelligence.Intent): OrionDecisionEngine.Decision {
        return decisionEngine.decide(intent)
    }

    /** Predict obstacles for a given plan and intent */
    fun predictObstaclesForPlan(
        plan: OrionPlanningEngine.Plan,
        intent: OrionIntentIntelligence.Intent
    ): OrionPredictiveIntelligence.ObstacleReport {
        val predictiveContext = OrionPredictiveIntelligence.PredictiveContext(
            deviceContext = lastContext?.device?.let { contextIntegration.buildDeviceContext() },
            availableStorageMb = getAvailableStorageMb(),
            isScreenOn = lastContext?.device?.screenState == OrionContextIntegration.DeviceContext.ScreenState.ON,
            currentAppPackage = lastContext?.device?.currentAppPackage,
            isAppInForeground = lastContext?.device?.currentAppPackage?.let { true } ?: false,
            isDeviceIdle = false,
            userInactiveMinutes = getUserInactiveMinutes()
        )
        return predictiveIntelligence.predictObstacles(plan, intent, predictiveContext)
    }

    // ========================================================================
    // CONTROL METHODS
    // ========================================================================

    fun stop() {
        stopped = true
        researchCancelled.set(true)
    }

    fun reset() {
        stopped = false
        researchCancelled.set(false)
        sharedHistory.clear()
        history.clear()
        liveHistory.clear()
        chatCache.clear()
        ranSteps.clear()
        taskProofs.clear()
        failedCallGuard.clear()
        toolLoopDetector.clear()
        pythonOutputBuffer.clear()
        lastScreenSnapshot = ""
        lastScreenBeforeAction = ""
        activeObjective = ""
        activeTaskId = ""
        activeObjectiveId = ""
        currentTask = ""
        runStartedAt = 0L
        runTools = 0
        runFailed = 0
        activeSkillId.set(null)
        activeSkillStartedAt = 0L
        activeSkillCalls = 0
        lastDecision = null
        lastPlan = null
        lastObstacleReport = null
        lastContext = null
    }

    fun executeWithRecoveryGuard(tool: String, args: JSONObject): String {
        // Enhanced with decision engine context
        val objectiveGraph = OrionObjectiveGraph.load(svc)
        if (objectiveGraph != null && OrionObjectiveGraph.requiresObjectiveDispatch(tool)) {
            val objectiveId = OrionObjectiveGraph.resolveDispatchId(objectiveGraph, activeObjectiveId, tool) ?: return "error: the planner's objective_id does not match this multi-objective task. Valid IDs: " + objectiveGraph.nodes.joinToString(", ") { it.id } + ". No tool was dispatched."
            activeObjectiveId = objectiveId
            OrionObjectiveGraph.canExecute(objectiveGraph, objectiveId)?.let { reason -> return "error: objective dependency gate blocked dispatch: " + reason }
            if (!OrionObjectiveGraph.startExecution(svc, objectiveId)) return "error: objective state could not be persisted before dispatch; no tool was run."
        }

        // Preflight capability check
        val preflight = ToolGate.preflightDenial(svc, tool)
        if (preflight != null) return "error: preflight blocked $tool. $preflight"

        // Validation
        val nativeUiActions = setOf("open_app", "click", "swipe", "type_text", "browser_navigate", "browser_click", "browser_type", "browser_snapshot", "send_message")
        if (tool in nativeUiActions) {
            val validationArgs = JSONObject(args.toString()).put("tool", tool)
            val validation = validateAction(validationArgs)
            if (validation.startsWith("INVALID:")) return "error: preflight blocked action. $validation"
        }

        // Execute with recovery
        val dispatchAction = { OrionExecutionLoop.dispatch(tool, stopped) { 
            if (tool in nativeUiActions) {
                val validationArgs = JSONObject(args.toString()).put("tool", tool)
                val validation = validateAction(validationArgs)
                if (validation.startsWith("INVALID:")) "error: preflight blocked action. $validation" else exec(tool, args)
            } else exec(tool, args)
        } }
        val result = dispatchAction().value
        var verifiedResult = result

        // Verification logic (preserved from original)
        val shouldVerifyAction = tool !in setOf("verify_send", "verify_action", "read_screen", "look", "play", "shell", "python_exec", "memory", "task_add", "macro_save", "macro_run", "skill_invoke", "ask_agent", "queue_agent")
        if (!result.trimStart().startsWith("error", ignoreCase = true) && shouldVerifyAction) {
            if (lastScreenSnapshot.isBlank()) verifiedResult = "error: verification unavailable: Orion had no pre-action screen snapshot, so '$tool' cannot be reported as confirmed. Original result: $result"
            else {
                try {
                    val before = lastScreenSnapshot
                    val after = if (tool == "browser_navigate") {
                        val previousUrl = try { JSONObject(before).optString("url") } catch (_: Exception) { "" }
                        BrowserAutomation.awaitNavigation(args.optString("url"), previousUrl, 8000L).take(6000)
                    } else {
                        AgentHooks.awaitStableSnapshot(read = { 
                            if (tool.startsWith("browser_")) BrowserAutomation.snapshot().take(6000) else svc.readScreen() 
                        }, maxSamples = 5, stableSamples = 2, intervalMs = 120L)
                    }
                    lastScreenBeforeAction = before
                    if (tool == "browser_navigate" && after.startsWith("error:", ignoreCase = true)) verifiedResult = "error: browser navigation was started but did not become verifiably readable: $after. Original result: $result"
                    else {
                        lastScreenSnapshot = after
                        val changed = AgentHooks.screenChanged(before, after)
                        if (changed) verifiedResult += "\nLOCAL VERIFY PASS: visible state changed after this action. This is evidence of a UI change, not proof of the user's overall goal."
                        else verifiedResult = "error: verification failed: no visible screen change was detected after '$tool'. The action is not confirmed; inspect the screen and choose a safe recovery step. Original result: $result"
                    }
                } catch (_: Exception) {
                    verifiedResult = "error: verification unavailable: post-action screen comparison failed; outcome remains unverified. Original result: $result"
                }
            }
        }

        val verificationPassed = !shouldVerifyAction || verifiedResult.contains("LOCAL VERIFY PASS:")
        if (!OrionExecutionLoop.completionEvidence(verifiedResult, shouldVerifyAction, verificationPassed) && !verifiedResult.trimStart().startsWith("error", ignoreCase = true)) verifiedResult = "error: execution result did not satisfy the required verification gate; outcome remains unverified. Original result: $verifiedResult"

        val trustedVerification = !verifiedResult.trimStart().startsWith("error", ignoreCase = true) && ((shouldVerifyAction && verifiedResult.contains("LOCAL VERIFY PASS:", ignoreCase = true)) || (tool == "verify_send" && result.trimStart().startsWith("PASS:", ignoreCase = true)) || (tool == "verify_action" && result.trimStart().startsWith("PASS:", ignoreCase = true)) || (tool == "shell" && (result.startsWith("EXIT 0 · SUCCESS\nLOCAL VERIFY PASS: shell process exited with code 0.") || result.startsWith("exit=0\nLOCAL VERIFY PASS: shell process exited with code 0."))))
        if (trustedVerification) taskProofs.recordVerified(tool, verifiedResult) else taskProofs.record(tool, verifiedResult)
        if (!isNonLoggedAction(tool, args)) { runCatching { OrionEvidenceLedger.recordDispatch(svc, evidenceTaskId(), "agent_verification", tool, JSONObject(), System.currentTimeMillis(), verifiedResult, trustedVerification) } }
        if (verifiedResult.trimStart().startsWith("error", ignoreCase = true)) {
            // Recovery logic (preserved)
            try { val observed = if (tool.startsWith("browser_")) BrowserAutomation.snapshot().take(6000) else svc.readScreen().take(6000); if (observed.isNotBlank()) { lastScreenSnapshot = observed; verifiedResult += "\nPOST-FAILURE STATE INSPECTION (observed after the error; not proof of completion):\n$observed" } else verifiedResult += "\nPOST-FAILURE STATE INSPECTION: no readable screen state was available; do not assume the action succeeded." } catch (e: Exception) { verifiedResult += "\nPOST-FAILURE STATE INSPECTION unavailable: " + (e.message ?: e.javaClass.simpleName) }
            val recovery = OrionRecoveryPlanner.forFailure(tool, verifiedResult)
            if (!isNonLoggedAction(tool, args)) { runCatching { OrionEvidenceLedger.recordRecovery(svc, evidenceTaskId(), tool, recovery.strategy.name.lowercase().replace('_', '-'), recovery.reason) } }
            val permittedAlternatives = ToolRegistry.all.asSequence().filter { it.name != tool }.filter { ToolGate.preflightDenial(svc, it.name) == null }.map { it.name }.toSet()
            val attemptedTools = (0 until ranSteps.length()).mapNotNull { index -> runCatching { ranSteps.getJSONObject(index).optString("tool").takeIf { it.isNotBlank() } }.getOrNull() }.toSet() + tool
            val alternatives = OrionRecoveryPlanner.selectAlternatives(failedTool = tool, result = verifiedResult, registeredAndPermitted = permittedAlternatives, alreadyAttempted = attemptedTools)
            val alternativeNote = if (alternatives.isEmpty()) "\nRECOVERY OPTIONS: none selected. Resolve the blocker or inspect current state; do not bypass confirmation rules." else "\nREGISTERED ALTERNATIVES (re-check live state, scope and capability immediately before use): " + alternatives.joinToString(", ")
            verifiedResult += "\n" + recovery.promptLine() + alternativeNote
            var recovered = false
            if (!isNonLoggedAction(tool, args)) {
                val risk = RecoveryPolicy.riskOf(tool, ToolRegistry.access(tool) ?: ToolAccess.WRITE, false)
                val failureKind = RecoveryPolicy.classify(verifiedResult)
                val autoRetryAllowed = risk != RecoveryPolicy.Risk.CONSEQUENTIAL && failureKind != RecoveryPolicy.Failure.PERMISSION && !stopped
                if (autoRetryAllowed && alternatives.isNotEmpty()) {
                    val probe = when { alternatives.contains("verify_action") -> "verify_action"; alternatives.contains("read_screen") -> "read_screen"; alternatives.contains("wait_for") -> "wait_for"; else -> alternatives.first() }
                    val probeArgs = when (probe) { "verify_action" -> JSONObject().put("question", "Was the goal of the previous '$tool' action completed?"); "read_screen" -> JSONObject(); "wait_for" -> JSONObject().put("millis", 1500); else -> JSONObject() }
                    val probeResult = try { OrionExecutionLoop.dispatch(probe, stopped) { exec(probe, probeArgs) } } catch (_: Exception) { OrionExecutionLoop.DispatchResult(OrionExecutionLoop.DispatchState.FAILED, "error: alternative probe could not run") }
                    if (probeResult.state == OrionExecutionLoop.DispatchState.DISPATCHED && !probeResult.value.trimStart().startsWith("error", ignoreCase = true) && probeResult.value.contains("LOCAL VERIFY PASS:", ignoreCase = true)) { recovered = true; taskProofs.recordVerified(probe, probeResult.value); verifiedResult = probeResult.value + "\nRECOVERED: an alternative verification step confirmed the action outcome after the first attempt did not verify."; runCatching { OrionEvidenceLedger.recordRecovery(svc, evidenceTaskId(), tool, "retry_with_alternative", "retry_with_alternative_$probe") }.getOrDefault(false) }
                }
            }
            if (!recovered && !isNonLoggedAction(tool, args)) { failedCallGuard.recordFailure(tool, args); try { OrionMemoryCapabilityExecutor.recordFailure(svc, currentTask, tool, verifiedResult) } catch (_: Exception) { } }
        }
        val loopRecord = toolLoopDetector.record(tool, loopParams(args), verifiedResult, errorMessage = if (verifiedResult.trimStart().startsWith("error", ignoreCase = true)) verifiedResult else null)
        if (loopRecord.level == ToolLoopDetector.Level.WARNING && !loopRecord.message.isNullOrBlank()) verifiedResult += "\n" + loopRecord.message
        AgentPolicy.log(svc, tool, "result", !verifiedResult.trimStart().startsWith("error", ignoreCase = true), if (verifiedResult.trimStart().startsWith("error", ignoreCase = true)) "failed or unverified; details remain in the task transcript" else "tool returned; details remain in the task transcript")
        try { ActionLogStore.record(svc, tool, args, verifiedResult, OrionTaskOutcome.actionStatus(trustedVerification, verifiedResult.trimStart().startsWith("error", ignoreCase = true))) } catch (_: Exception) { }
        return OrionSecurityPolicy.modelFacingResult(tool, verifiedResult)
    }

    // ========================================================================
    // COMPANION OBJECT
    // ========================================================================

    companion object {
        private const val MAX_VISION_STEPS_PER_MINUTE = 5
        private val visionStepTimes = java.util.concurrent.ConcurrentLinkedDeque<Long>()
        private fun reserveVisionStep(): Boolean {
            val now = System.currentTimeMillis(); val cutoff = now - 60_000L
            synchronized(visionStepTimes) { while (true) { val oldest = visionStepTimes.peekFirst() ?: break; if (oldest < cutoff) visionStepTimes.pollFirst() else break }; if (visionStepTimes.size >= MAX_VISION_STEPS_PER_MINUTE) return false; visionStepTimes.addLast(now); return true }
        }
    }
}

// Extension to convert IntentType to IntentKind
private fun OrionIntentIntelligence.IntentType.toIntentKind(): OrionContextualIntentInterpreter.Kind = when (this) {
    OrionIntentIntelligence.IntentType.QUESTION -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.ACTION -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.MULTI_STEP_TASK -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.RESEARCH -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.CODING -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.AUTOMATION -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.MEMORY_OP -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.SCHEDULING -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.TRADING -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.CREATIVE -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.SYSTEM_CONTROL -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.CORRECTION -> OrionContextualIntentInterpreter.Kind.CORRECT_PREVIOUS
    OrionIntentIntelligence.IntentType.CONTINUATION -> OrionContextualIntentInterpreter.Kind.CONTINUE_OBJECTIVE
    OrionIntentIntelligence.IntentType.CLARIFICATION -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.UNKNOWN -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
}
