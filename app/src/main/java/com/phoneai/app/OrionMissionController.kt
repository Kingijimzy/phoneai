package com.phoneai.app

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*

/**
 * Phase 0C: Mission Ownership
 *
 * Single authoritative owner of every objective from intake to verified completion.
 * Integrates with OrionUnifiedRuntime's decision pipeline and provides:
 * - Persistent mission lifecycle (Room via OrionMissionStore)
 * - State machine enforcement (MissionTransitionValidator)
 * - Specialist delegation with bounded concurrency (OrionAgentFactory)
 * - Recovery as mission-level state (replan, resume, retry)
 * - Evidence aggregation for observability (OrionMissionEvidence)
 */
internal class OrionMissionController(
    private val context: Context,
    private val unifiedRuntime: OrionUnifiedRuntime,
    private val svc: PhoneAiService,
    private val ui: AgentUi,
    private val tools: Tools,
    private val localExecutor: Local,
    private val pythonExecutor: OrionPythonCapabilityExecutor,
    private val commandNim: Nim
) {

    private val store = OrionMissionStore(context)
    private val agentFactory = OrionAgentFactory(
        context = context,
        svc = svc,
        ui = ui,
        tools = tools,
        localExecutor = localExecutor,
        pythonExecutor = pythonExecutor,
        commandNim = commandNim,
        controller = this
    )

    // Current mission state
    @Volatile private var currentMission: OrionMission? = null
    @Volatile private var isPaused = false

    // Concurrency bounds
    internal val MAX_CONCURRENT_SPECIALISTS = 3
    private val SPECIALIST_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    private val MAX_MISSION_STEPS = 20
    private val MAX_MISSION_DURATION_MS = 30 * 60 * 1000L // 30 minutes

    // Background scope for specialist coroutines
    private val missionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Start a new mission from a raw command string.
     * Runs the full decision pipeline internally and starts the mission.
     * Returns the mission ID for tracking.
     */
    fun startMission(command: String): String {
        return runBlocking { startMissionAsync(command) }
    }

    private suspend fun startMissionAsync(command: String): String {
        // Build context
        val sessionData = OrionContextIntegration.SessionData(
            command = command,
            modality = "COMMAND",
            activeObjective = contextualActiveObjective(),
            sessionStart = System.currentTimeMillis()
        )

        val context = unifiedRuntime.contextIntegration.buildContext(
            command, "COMMAND", contextualActiveObjective(), sessionData,
            runtimeSnapshot = OrionContextIntegration.RuntimeSnapshot(
                stopped = unifiedRuntime.stopped,
                activeSkillId = unifiedRuntime.activeSkillId.get(),
                runTools = unifiedRuntime.runTools,
                runFailed = unifiedRuntime.runFailed
            )
        )

        // Analyze intent
        val intent = unifiedRuntime.intentIntelligence.analyzeIntent(
            input = command,
            conversationHistory = unifiedRuntime.recentConversationTurns().map { it.second },
            activeObjective = contextualActiveObjective(),
            context = context
        )

        // Decide
        val decision = unifiedRuntime.decisionEngine.decide(
            intent, context,
            unifiedRuntime.reasoningEngine,
            unifiedRuntime.planningEngine,
            unifiedRuntime.policyEngine
        )

        // Start mission with the decision
        return startMissionWithDecision(intent, decision)
    }

    /**
     * Internal start with pre-computed intent and decision.
     */
    private suspend fun startMissionWithDecision(
        intent: OrionIntentIntelligence.Intent,
        decision: OrionDecisionEngine.Decision
    ): String {
        // If there's an active mission that can't be interrupted, queue or reject
        if (currentMission?.isActive == true && currentMission?.status != OrionMissionStatus.SUSPENDED) {
            throw IllegalStateException("Mission ${currentMission!!.id} is ${currentMission!!.status}; cannot start new mission. Pause or complete first.")
        }

        val requestId = "req_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"
        val mission = OrionMission.newMission(requestId, intent, decision)
        currentMission = mission
        store.save(mission)
        unifiedRuntime.activeObjective = intent.primaryObjective
        unifiedRuntime.activeTaskId = mission.id
        transitionTo(OrionMissionStatus.PLANNED)

        // Launch execution in background
        missionScope.launch {
            try {
                executeCurrentMission(OrionUnifiedRuntime.Modality.COMMAND)
            } catch (e: Exception) {
                // Mission execution error - transition to failed
                transitionTo(OrionMissionStatus.FAILED, failureReason = "Execution error: ${e.message}")
            }
        }

        return mission.id
    }

    /**
     * Execute the current mission's plan. Called by unified runtime.
     * This is the main execution loop that runs until completion, failure, or pause.
     */

    /** Get contextual active objective (mirrors unified runtime's logic) */
    private fun contextualActiveObjective(): String =
        unifiedRuntime.activeObjective.ifBlank {
            runCatching { OrionTasksCapabilityExecutor.pendingCheckpoint(svc)?.optString("command").orEmpty() }
                .getOrDefault("")
        }.ifBlank {
            runCatching { OrionObjectiveGraph.load(svc)?.objective.orEmpty() }.getOrDefault("")
        }

    /**
     * Execute the current mission's plan. Called by unified runtime.
     * This is the main execution loop that runs until completion, failure, or pause.
     */
    suspend fun executeCurrentMission(modality: OrionUnifiedRuntime.Modality): MissionExecutionResult {
        val mission = currentMission ?: throw IllegalStateException("No active mission to execute")
        if (!mission.status.canResume && mission.status != OrionMissionStatus.PLANNED) {
            throw IllegalStateException("Mission ${mission.id} is ${mission.status}; cannot execute")
        }

        transitionTo(OrionMissionStatus.EXECUTING)
        val plan = mission.plan ?: throw IllegalStateException("Mission ${mission.id} has no execution plan")
        val decision = mission.decision

        return executePlanLoop(mission, plan, decision, modality)
    }

    private suspend fun executePlanLoop(
        mission: OrionMission,
        plan: OrionPlanningEngine.ExecutionPlan,
        decision: OrionDecisionEngine.Decision,
        modality: OrionUnifiedRuntime.Modality
    ): MissionExecutionResult {
        var currentPlan = plan
        val completed = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        val skipped = mutableSetOf<String>()
        val attemptedTools = mutableSetOf<String>()
        val notes = mutableListOf<String>()
        var replans = 0
        var stepsRun = 0

        // Pre-arm predictive mitigations
        val obstacleReport = unifiedRuntime.predictObstaclesForPlan(currentPlan, decision.intent)
        obstacleReport.mitigationPlan.forEach { ui.note("[PREDICTIVE] $it") }

        val startTime = SystemClock.elapsedRealtime()
        while (stepsRun < MAX_MISSION_STEPS &&
               SystemClock.elapsedRealtime() - startTime < MAX_MISSION_DURATION_MS &&
               !unifiedRuntime.stopped &&
               !isPaused) {

            // Check for pause request
            if (isPaused) {
                checkpointAndSuspend("owner_paused")
                return MissionExecutionResult(
                    missionId = mission.id,
                    status = OrionMissionStatus.SUSPENDED,
                    completed = completed,
                    failed = failed,
                    skipped = skipped,
                    notes = notes + "Paused by owner",
                    replans = replans,
                    stoppedEarly = true
                )
            }

            // Find next ready step
            val ready = currentPlan.steps.firstOrNull { step ->
                step.status == OrionPlanningEngine.PlanStepStatus.PENDING &&
                    step.dependsOn.all { it in completed || it in skipped }
            } ?: break

            stepsRun++
            ui.phase("${ready.name} (${ready.tool})")
            unifiedRuntime.runTools++

            // Acknowledge for phone-mutating or multi-step
            if (modality != OrionUnifiedRuntime.Modality.CHAT || currentPlan.steps.size > 2 ||
                ready.tool in PHONE_MUTATING_TOOLS) {
                ui.say("On it, Boss.")
            }

            val args = unifiedRuntime.buildStepArgs(ready, decision.intent)
            attemptedTools += ready.tool

            // Check preflight
            val preflight = ToolGate.preflightDenial(svc, ready.tool)
            var result = if (preflight != null) "error: preflight blocked $ready.tool. $preflight"
            else unifiedRuntime.executeWithRecoveryGuard(ready.tool, args)

            if (unifiedRuntime.isStepSuccess(result)) {
                completed += ready.stepId
                notes += "${ready.stepId} ok via ${ready.tool}"
            } else {
                failed += ready.stepId
                unifiedRuntime.runFailed++
                notes += "${ready.stepId} failed via ${ready.tool}: ${result.take(160)}"

                // Real replanning: alternate tool, not the same call again
                if (replans < MAX_REPLANS && !unifiedRuntime.stopped && !isPaused) {
                    val replan = unifiedRuntime.planningEngine.replanFromFailure(
                        plan = currentPlan,
                        failedStepId = ready.stepId,
                        error = result,
                        intent = decision.intent,
                        context = unifiedRuntime.lastContext,
                        alreadyAttempted = attemptedTools
                    )
                    if (replan.succeeded) {
                        replans++
                        currentPlan = replan.plan
                        notes += "replan: ${replan.note}"
                        ui.note("[RECOVERY] ${replan.note}")
                        OrionEvidenceLedger.recordRecovery(
                            svc, mission.id, ready.tool, "replan", replan.note
                        )
                        continue // retry with new plan
                    } else {
                        notes += "replan unavailable: ${replan.note}"
                    }
                }
            }

            // Check for mission-level failure (all alternatives exhausted)
            if (failed.size > currentPlan.steps.size / 2 && replans >= MAX_REPLANS) {
                val failureReason = "Mission failed after $replans replans. Last error: ${notes.lastOrNull() ?: "unknown"}"
                transitionTo(OrionMissionStatus.FAILED, failureReason = failureReason)
                return MissionExecutionResult(
                    missionId = mission.id,
                    status = OrionMissionStatus.FAILED,
                    completed = completed,
                    failed = failed,
                    skipped = skipped,
                    notes = notes + failureReason,
                    replans = replans,
                    stoppedEarly = false
                )
            }
        }

        // All steps processed - verify at objective level
        val outcome = MissionOutcome(
            plan = currentPlan,
            decision = decision,
            completed = completed,
            failed = failed,
            skipped = skipped,
            notes = notes,
            replans = replans,
            stoppedEarly = unifiedRuntime.stopped || isPaused
        )

        val verification = unifiedRuntime.verifyOutcome(outcome, decision.intent, unifiedRuntime.lastContext)

        if (verification.passed) {
            transitionTo(OrionMissionStatus.VERIFYING)
            transitionTo(OrionMissionStatus.COMPLETED)
            return MissionExecutionResult(
                missionId = mission.id,
                status = OrionMissionStatus.COMPLETED,
                completed = completed,
                failed = failed,
                skipped = skipped,
                notes = notes,
                replans = replans,
                stoppedEarly = false,
                verification = verification
            )
        } else {
            transitionTo(OrionMissionStatus.FAILED, failureReason = verification.reasons.joinToString("; "))
            return MissionExecutionResult(
                missionId = mission.id,
                status = OrionMissionStatus.FAILED,
                completed = completed,
                failed = failed,
                skipped = skipped,
                notes = notes,
                replans = replans,
                stoppedEarly = false,
                verification = verification
            )
        }
    }

    /**
     * Pause the current mission - checkpoints state for resume.
     */
    suspend fun pauseMission(): Boolean {
        val mission = currentMission ?: return false
        if (!mission.isActive) return false

        isPaused = true
        // Wait for execution loop to reach checkpoint
        var waited = 0L
        while (currentMission?.status != OrionMissionStatus.SUSPENDED && waited < 5000) {
            delay(100)
            waited += 100
        }
        return currentMission?.status == OrionMissionStatus.SUSPENDED
    }

    /**
     * Resume a paused or failed mission.
     * For FAILED: re-plans from the failure point.
     * For SUSPENDED: continues from checkpoint.
     */
    suspend fun resumeMission(missionId: String, strategy: ResumeStrategy = ResumeStrategy.CONTINUE): MissionExecutionResult {
        val mission = store.getById(missionId).await() ?: throw IllegalArgumentException("Mission $missionId not found")
        if (!mission.canResume) throw IllegalStateException("Mission $missionId cannot be resumed from ${mission.status}")

        currentMission = mission
        isPaused = false
        unifiedRuntime.activeObjective = mission.intent.primaryObjective
        unifiedRuntime.activeTaskId = mission.id

        return when (strategy) {
            ResumeStrategy.CONTINUE -> {
                if (mission.status == OrionMissionStatus.SUSPENDED || mission.status == OrionMissionStatus.CHECKPOINTED) {
                    transitionTo(OrionMissionStatus.EXECUTING)
                    val plan = mission.plan ?: throw IllegalStateException("No plan to continue")
                    executePlanLoop(mission, plan, mission.decision, OrionUnifiedRuntime.Modality.RESUME)
                } else {
                    throw IllegalStateException("CONTINUE only valid for SUSPENDED/CHECKPOINTED, not ${mission.status}")
                }
            }
            ResumeStrategy.REPLAN -> {
                // Failed mission: build new plan from failure point
                transitionTo(OrionMissionStatus.INTAKE)
                val newPlan = unifiedRuntime.planningEngine.replanFromFailure(
                    plan = mission.plan!!,
                    failedStepId = mission.evidence.lastOrNull()?.tool ?: "unknown",
                    error = mission.failureReason ?: "unknown",
                    intent = mission.intent,
                    context = unifiedRuntime.lastContext,
                    alreadyAttempted = mission.evidence.map { it.tool }.toSet()
                )
                if (newPlan.succeeded) {
                    val updatedMission = mission.withPlan(newPlan.plan).withStatus(OrionMissionStatus.PLANNED)
                    store.update(updatedMission)
                    currentMission = updatedMission
                    transitionTo(OrionMissionStatus.EXECUTING)
                    executePlanLoop(updatedMission, newPlan.plan, mission.decision, OrionUnifiedRuntime.Modality.RESUME)
                } else {
                    throw IllegalStateException("Replan failed: ${newPlan.note}")
                }
            }
            ResumeStrategy.RETRY_FULL -> {
                // Retry from beginning with fresh plan
                transitionTo(OrionMissionStatus.INTAKE)
                val decision = unifiedRuntime.decisionEngine.decide(
                    mission.intent, unifiedRuntime.lastContext,
                    unifiedRuntime.reasoningEngine, unifiedRuntime.planningEngine, unifiedRuntime.policyEngine
                )
                val updatedMission = mission.withPlan(decision.executionPlan!!).withStatus(OrionMissionStatus.PLANNED)
                store.update(updatedMission)
                currentMission = updatedMission
                transitionTo(OrionMissionStatus.EXECUTING)
                executePlanLoop(updatedMission, decision.executionPlan!!, decision, OrionUnifiedRuntime.Modality.RESUME)
            }
        }
    }

    /**
     * Cancel the current mission.
     */
    suspend fun cancelMission(): Boolean {
        val mission = currentMission ?: return false
        if (mission.isTerminal) return false

        isPaused = false
        unifiedRuntime.stop()
        transitionTo(OrionMissionStatus.CANCELLED, failureReason = "Cancelled by owner")
        return true
    }

    /**
     * Create a specialist agent for a sub-task.
     * Returns the specialist session ID.
     */
    suspend fun delegateToSpecialist(
        specialism: OrionSpecialism,
        objective: String,
        allowedTools: Set<String>? = null,
        contextSnapshot: OrionContextIntegration.UnifiedContext? = null
    ): String {
        val mission = currentMission ?: throw IllegalStateException("No active mission to delegate from")

        // Check concurrency limit
        val activeSpecialists = mission.specialistReceipts.count { it.verdict == SpecialistVerdict.UNVERIFIED }
        if (activeSpecialists >= MAX_CONCURRENT_SPECIALISTS) {
            throw IllegalStateException("Specialist concurrency limit reached ($MAX_CONCURRENT_SPECIALISTS). Wait for one to complete.")
        }

        val session = agentFactory.createSession(
            parentMissionId = mission.id,
            specialism = specialism,
            objective = objective,
            allowedTools = allowedTools,
            contextSnapshot = contextSnapshot ?: unifiedRuntime.lastContext!!
        )

        return session.sessionId
    }

    /**
     * Collect a specialist's result and integrate into parent mission.
     */
    suspend fun collectSpecialist(sessionId: String): OrionSpecialistReceipt {
        val receipt = agentFactory.collectSession(sessionId).await()
        currentMission?.let { mission ->
            val updated = mission.withReceipt(receipt)
            currentMission = updated
            store.update(updated)
        }
        return receipt
    }

    /**
     * Terminate a specialist early.
     */
    suspend fun terminateSpecialist(sessionId: String, reason: String): OrionSpecialistReceipt {
        val receipt = agentFactory.terminateSession(sessionId, reason).await()
        currentMission?.let { mission ->
            val updated = mission.withReceipt(receipt)
            currentMission = updated
            store.update(updated)
        }
        return receipt
    }

    /** Get current mission for UI/observability */
    fun getCurrentMission(): OrionMission? = currentMission

    /** Get mission evidence for observatory */
    suspend fun getMissionEvidence(missionId: String): OrionMissionEvidence? {
        val mission = store.getById(missionId).await() ?: return null
        return buildEvidence(mission)
    }

    /** Load active mission on startup (survives process death) */
    suspend fun restoreActiveMission(): OrionMission? {
        val mission = store.getActiveMission().await()
        currentMission = mission
        return mission
    }

    /** Check if a new mission can be started */
    fun canStartNewMission(): Boolean = currentMission?.isActive != true

    /** Pause state for power controller */
    fun setPaused(paused: Boolean) {
        isPaused = paused
    }

    // ------------------------------------------------------------------------
    // PRIVATE HELPERS
    // ------------------------------------------------------------------------

    private fun transitionTo(
        newStatus: OrionMissionStatus,
        checkpointJson: String? = null,
        failureReason: String? = null,
        completedAt: Long? = null
    ) {
        currentMission?.let { mission ->
            val transition = MissionTransitionValidator.validate(mission.status, newStatus)
            if (transition is MissionTransition.Invalid) {
                throw IllegalStateException("Mission ${mission.id}: ${transition.reason}")
            }
            val updated = mission.withStatus(
                newStatus,
                checkpointJson = checkpointJson,
                failureReason = failureReason,
                completedAt = completedAt
            )
            currentMission = updated
            store.update(updated)
        }
    }

    private fun checkpointAndSuspend(reason: String) {
        currentMission?.let { mission ->
            val checkpoint = buildString {
                appendLine("CHECKPOINT $reason")
                appendLine("timestamp=${System.currentTimeMillis()}")
                appendLine("objective=${mission.intent.primaryObjective}")
                appendLine("status=${mission.status}")
            }
            transitionTo(OrionMissionStatus.SUSPENDED, checkpointJson = checkpoint)
            isPaused = true
        }
    }

    private fun buildEvidence(mission: OrionMission): OrionMissionEvidence {
        val executedSteps = mission.evidence.map { outcome ->
            StepEvidence(
                stepId = outcome.tool,
                tool = outcome.tool,
                args = "{}", // args not stored in outcome
                result = outcome.result,
                verified = outcome.verified,
                timestamp = outcome.timestamp
            )
        }

        val failures = mission.evidence.filter { !it.verified && it.tool != "verify_action" && it.tool != "verify_send" }.map { outcome ->
            FailureEvidence(
                stepId = outcome.tool,
                tool = outcome.tool,
                error = outcome.result,
                recoveryAttempted = false, // TODO: track from replans
                recoveryStrategy = null,
                timestamp = outcome.timestamp
            )
        }

        return OrionMissionEvidence(
            missionId = mission.id,
            decisionExplanation = mission.decision.rationale,
            rejectedAlternatives = mission.decision.alternativesRejected.map { "${it.strategyId}: ${it.reason}" },
            plan = mission.plan?.let { Json.encodeToString(OrionPlanningEngine.ExecutionPlan.serializer(), it) } ?: "",
            executedSteps = executedSteps,
            specialistReceipts = mission.specialistReceipts,
            failures = failures,
            recoveryActions = mission.notes.filter { it.contains("replan") },
            finalVerification = "", // VerificationOutcome not serializable directly
            completedAt = mission.completedAt
        )
    }

    companion object {
        private const val MAX_REPLANS = 2
        private val PHONE_MUTATING_TOOLS = setOf("open_app", "click", "type_text", "swipe", "send_message")
    }

    enum class ResumeStrategy { CONTINUE, REPLAN, RETRY_FULL }
}

data class MissionExecutionResult(
    val missionId: String,
    val status: OrionMissionStatus,
    val completed: Set<String>,
    val failed: Set<String>,
    val skipped: Set<String>,
    val notes: List<String>,
    val replans: Int,
    val stoppedEarly: Boolean,
    val verification: OrionUnifiedRuntime.VerificationOutcome? = null
)

data class MissionOutcome(
    val plan: OrionPlanningEngine.ExecutionPlan,
    val decision: OrionDecisionEngine.Decision,
    val completed: Set<String>,
    val failed: Set<String>,
    val skipped: Set<String>,
    val notes: List<String>,
    val replans: Int,
    val stoppedEarly: Boolean
)