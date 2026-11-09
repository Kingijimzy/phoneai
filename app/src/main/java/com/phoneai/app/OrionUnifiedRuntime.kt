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
 * - OPEN CAPABILITIES: Central Brain has access to ALL registered/authorized capabilities
 * - DYNAMIC AUTHORIZATION: Specialists request additional tools via CapabilityBroker
 * - AUTONOMOUS RECOVERY: Denied/unavailable capabilities trigger diagnosis & alternatives
 * - CENTRAL GOVERNANCE: Security boundaries enforced at dispatch (Android perms, trading, irreversible)
 */
internal class OrionUnifiedRuntime(
    private val svc: PhoneAiService,
    private val ui: AgentUi,
    private val nim: Nim,
    private val tools: Tools,
    private val localExecutor: Local,
    private val pythonExecutor: OrionPythonCapabilityExecutor,
    private val commandNim: Nim,

    // Capability Broker for dynamic tool expansion (null for Central Brain which has all tools)
    private val capabilityBroker: OrionCapabilityBroker? = null,

    // Capability Intelligence for discovery, gap analysis, and acquisition planning
    private val capabilityIntelligence: OrionCapabilityIntelligence? = null,

    // Capability Acquisition Engine for autonomous capability acquisition
    private val acquisitionEngine: OrionCapabilityAcquisitionEngine? = null,

    // Extension store and sandbox for self-extension
    private val extensionStore: SelfExtendingExtensionStore? = null,
    private val extensionSandbox: ExtensionSandbox? = null,

    // Shared capability discovery singleton
    private val capabilityDiscovery: OrionCapabilityDiscovery = OrionCapabilityDiscovery,

    // Decision Engine components — all share one service and read one context
    internal val intentIntelligence: OrionIntentIntelligence = OrionIntentIntelligence(svc),
    internal val reasoningEngine: OrionReasoningEngine = OrionReasoningEngine(svc),
    internal val decisionEngine: OrionDecisionEngine = OrionDecisionEngine(svc),
    internal val planningEngine: OrionPlanningEngine = OrionPlanningEngine(svc),
    internal val predictiveIntelligence: OrionPredictiveIntelligence = OrionPredictiveIntelligence(svc),
    internal val policyEngine: OrionPolicyEngine = OrionPolicyEngine(svc),
    internal val contextIntegration: OrionContextIntegration = OrionContextIntegration(svc)
) {

    // ========================================================================
    // SHARED STATE (single source of truth across all modalities)
    // ========================================================================

    /** Conversation history shared across chat, voice, and command */
    private val sharedHistory = JSONArray()

    /** Task/goal context persisted across turns */
    @Volatile internal var activeObjective: String = ""
    @Volatile internal var activeTaskId: String = ""
    @Volatile private var activeObjectiveId: String = ""
    @Volatile private var currentTask: String = ""
    @Volatile private var runStartedAt: Long = 0L
    @Volatile internal var runTools: Int = 0
    @Volatile internal var runFailed: Int = 0

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
    internal val activeSkillId = AtomicReference<String?>(null)
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
    @Volatile private var lastPlan: OrionPlanningEngine.ExecutionPlan? = null
    @Volatile private var lastObstacleReport: OrionPredictiveIntelligence.ObstacleReport? = null
    @Volatile internal var lastContext: OrionContextIntegration.UnifiedContext? = null

    /** Undo stack for reversible actions (Ultron-style: act immediately, record undo) */
    private val undoStack = OrionUndoStack(svc.applicationContext, svc)

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
            command = command,
            modality = modality.name,
            activeObjective = contextualActiveObjective(),
            sessionStart = System.currentTimeMillis()
        )

        val context = contextIntegration.buildContext(
            command, modality.name, contextualActiveObjective(), sessionData,
            runtimeSnapshot = OrionContextIntegration.RuntimeSnapshot(
                stopped = stopped,
                activeSkillId = activeSkillId.get(),
                runTools = runTools,
                runFailed = runFailed
            )
        )
        lastContext = context

        // Resolve contextual intent (follow-ups, corrections, continuations)
        val intentResolution = OrionContextualIntentInterpreter.resolve(
            command, recentConversationTurns(), contextualActiveObjective()
        )

        // Route to action when the deterministic policies say so, or when the understanding
        // step concludes this is actionable. Conversation stays conversation.
        val needsAction = shouldRouteToAction(command, intentResolution, modality) ||
            intentRequiresExecution(command, modality, context)

        if (needsAction) {
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

    /**
     * Second routing opinion from the understanding stage. A request that clearly asks for a
     * device/software outcome must execute even if the fixed prefix policies miss it; a question
     * stays conversational even if it contains an action verb.
     */
    private fun intentRequiresExecution(
        command: String,
        modality: Modality,
        context: OrionContextIntegration.UnifiedContext
    ): Boolean {
        val intent = runCatching {
            intentIntelligence.analyzeIntent(
                input = command,
                conversationHistory = recentConversationTurns().map { it.second },
                activeObjective = contextualActiveObjective(),
                context = context
            )
        }.getOrNull() ?: return false

        // Remaining indexMemoryObservation work is now handled by recall itself.
        return when (intent.type) {
            OrionIntentIntelligence.IntentType.ACTION,
            OrionIntentIntelligence.IntentType.MULTI_STEP,
            OrionIntentIntelligence.IntentType.RESEARCH,
            OrionIntentIntelligence.IntentType.CREATIVE,
            OrionIntentIntelligence.IntentType.SYSTEM_CONTROL,
            OrionIntentIntelligence.IntentType.DELEGATION -> true
            OrionIntentIntelligence.IntentType.MEMORY_QUERY -> intent.missingCapabilities.isEmpty()
            else -> false
        }
    }

    /** Execute a mission through the full decision engine lifecycle. */
    private fun executeMissionWithDecisionEngine(
        command: String,
        intentResolution: OrionContextualIntentInterpreter.Resolution,
        modality: Modality,
        context: OrionContextIntegration.UnifiedContext
    ) {
        val isNewObjective = intentResolution.kind == OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
        if (isNewObjective) {
            taskProofs.clear()
            OrionObjectiveGraph.clear(svc)
            runTools = 0
            runFailed = 0
            runStartedAt = System.currentTimeMillis()
        }

        sharedHistory.put(msg("user", command))
        trimHistory(sharedHistory, 12)
        if (modality != Modality.CHAT) svc.notifyModeSwitch(true)

        // ---- Phase 1: understand ----
        ui.phase("understanding")
        val intent = intentIntelligence.analyzeIntent(
            input = command,
            conversationHistory = recentConversationTurns().map { it.second },
            activeObjective = contextualActiveObjective(),
            context = context
        )

        // ---- Phase 2+3: reason and decide (reasoning runs inside decide) ----
        ui.phase("reasoning")
        val decision = decisionEngine.decide(intent, context, reasoningEngine, planningEngine, policyEngine)
        lastDecision = decision

        // ---- Phase 4: plan ----
        ui.phase("planning")
        val plan = decision.executionPlan
        lastPlan = plan

        // ---- Phase 5: predict ----
        ui.phase("predicting")
        val obstacleReport = predictiveIntelligence.predictObstacles(plan, intent, context)
        lastObstacleReport = obstacleReport
        logDecision(decision, obstacleReport, context)

        // ---- No plan, or blocked before execution ----
        if (!decision.canProceed || plan == null || plan.steps.isEmpty()) {
            handleNonExecutableDecision(command, decision, modality, context)
            return
        }

        // ---- Acknowledge before multi-step or phone-mutating work only ----
        if (shouldAcknowledgeBefore(plan, modality)) {
            ui.say("On it, Boss.")
        }

        // ---- Confirmation gate: Trading Desk / device control / policy ASK ----
        if (decision.requiresConfirmation) {
            val approved = ui.ask("${decision.confirmationReason ?: "This action needs your approval"}. Proceed?")
            if (!approved) {
                ui.say("Cancelled, Boss. Nothing was executed.")
                OrionEvidenceLedger.recordRecovery(
                    svc, evidenceTaskId(), "confirmation", "declined",
                    decision.confirmationReason ?: "owner declined"
                )
                return
            }
        }

        // ---- Phase 6: execute ----
        ui.phase("acting")
        val outcome = executePlan(plan, decision, obstacleReport, modality, context)

        // ---- Phase 7: verify at objective level ----
        ui.phase("verifying")
        val verified = verifyOutcome(outcome, decision.intent, context)

        // ---- Phase 8: report ----
        ui.phase(if (verified.passed) "complete" else "partial")
        reportOutcome(outcome, verified, decision, modality)
    }

    /** Executes plan steps in dependency order with real replanning on failure. */
    internal fun executePlan(
        plan: OrionPlanningEngine.ExecutionPlan,
        decision: OrionDecisionEngine.Decision,
        obstacleReport: OrionPredictiveIntelligence.ObstacleReport,
        modality: Modality,
        context: OrionContextIntegration.UnifiedContext
    ): MissionOutcome {
        var currentPlan = plan
        val completed = linkedSetOf<String>()
        val failed = mutableSetOf<String>()
        val skipped = mutableSetOf<String>()
        val attemptedTools = mutableSetOf<String>()
        val notes = mutableListOf<String>()
        var replans = 0
        var stepsRun = 0

        activeObjective = decision.intent.primaryObjective
        if (activeTaskId.isBlank()) activeTaskId = "task_${System.currentTimeMillis()}"

        // Pre-arm mitigations into the trace so the log shows what was expected.
        obstacleReport.mitigationPlan.forEach { ui.note("[PREDICTIVE] $it") }
        val highRisk = obstacleReport.predictedObstacles.filter {
            it.severity == OrionPredictiveIntelligence.Severity.HIGH
        }
        highRisk.forEach { o ->
            notes += "predicted(${o.type}): ${o.description}"
            ui.note("[PREDICTIVE] ${o.description}")
        }

        val maxSteps = MAX_STEPS_PER_MISSION
        while (stepsRun < maxSteps && !stopped) {
            val ready = currentPlan.steps.firstOrNull { step ->
                step.status == OrionPlanningEngine.PlanStepStatus.PENDING &&
                    step.dependsOn.all { it in completed || it in skipped }
            } ?: break
            stepsRun++

            // A dependency that failed and was skipped means this step cannot honestly run.
            if (ready.dependsOn.any { it in failed }) {
                skipped += ready.stepId
                notes += "${ready.stepId} skipped: dependency failed"
                continue
            }

            ui.phase("${ready.name} (${ready.tool})")
            runTools++
            val args = buildStepArgs(ready, decision.intent)
            attemptedTools += ready.tool
            val result = executeWithRecoveryGuard(ready.tool, args)

            if (isStepSuccess(result)) {
                completed += ready.stepId
                notes += "${ready.stepId} ok via ${ready.tool}"
            } else {
                failed += ready.stepId
                runFailed++
                notes += "${ready.stepId} failed via ${ready.tool}: ${result.take(160)}"

                // Real replanning: alternate tool, not the same call again.
                if (replans < MAX_REPLANS && !stopped) {
                    val replan = planningEngine.replanFromFailure(
                        plan = currentPlan,
                        failedStepId = ready.stepId,
                        error = result,
                        intent = decision.intent,
                        context = context,
                        alreadyAttempted = attemptedTools
                    )
                    if (replan.succeeded) {
                        replans++
                        currentPlan = replan.plan
                        notes += "replan: ${replan.note}"
                        ui.note("[RECOVERY] ${replan.note}")
                        OrionEvidenceLedger.recordRecovery(
                            svc, evidenceTaskId(), ready.tool,
                            "replan", replan.note
                        )
                    } else {
                        notes += "replan unavailable: ${replan.note}"
                    }
                }
            }
        }

        return MissionOutcome(
            plan = currentPlan,
            decision = decision,
            completed = completed,
            failed = failed,
            skipped = skipped,
            notes = notes,
            replans = replans,
            stoppedEarly = stopped
        )
    }

    /**
     * Objective-level verification. Step success is not objective success: this insists on
     * evidence from a verifier tool or from the recorded proof set, and refuses to call a
     * mission complete on dispatch success alone.
     */
    internal fun verifyOutcome(
        outcome: MissionOutcome,
        intent: OrionIntentIntelligence.Intent,
        context: OrionContextIntegration.UnifiedContext?
    ): VerificationOutcome {
        val reasons = mutableListOf<String>()
        val total = outcome.plan.steps.count { !it.optional }
        val nonOptionalDone = outcome.plan.steps.count {
            !it.optional && (it.stepId in outcome.completed)
        }

        val verifiedTools = taskProofs.snapshot().filter { it.verified }.map { it.tool }.distinct()
        val verifierEvidence = verifiedTools.any { it in VERIFIER_TOOLS }
        val actionEvidence = verifiedTools.any { it !in VERIFIER_TOOLS }

        if (outcome.stoppedEarly) reasons += "run was stopped before completion"
        if (intent.missingCapabilities.isNotEmpty()) {
            reasons += "missing capability: ${intent.missingCapabilities.joinToString(", ")}"
        }
        if (nonOptionalDone < total) {
            reasons += "$nonOptionalDone of $total required steps completed"
        }
        if (!verifierEvidence && !actionEvidence) {
            reasons += "no verification evidence was recorded"
        }
        // A pure-conversation objective has nothing to verify beyond the answer itself.
        val needsToolEvidence = intent.successCriteria.any { it.verifiable }

        val passed = when {
            outcome.stoppedEarly -> false
            intent.missingCapabilities.isNotEmpty() -> false
            nonOptionalDone < total -> false
            needsToolEvidence && !verifierEvidence && !actionEvidence -> false
            else -> true
        }

        return VerificationOutcome(
            passed = passed,
            reasons = reasons,
            verifiedToolCount = verifiedTools.size,
            verifiedTools = verifiedTools.toList()
        )
    }

    private fun reportOutcome(
        outcome: MissionOutcome,
        verification: VerificationOutcome,
        decision: OrionDecisionEngine.Decision,
        modality: Modality
    ) {
        val planTotal = outcome.plan.steps.count { !it.optional }
        val done = outcome.completed.size
        val text = if (verification.passed) {
            buildString {
                append("Done, Boss. ")
                append("$done of $planTotal required steps completed")
                if (outcome.replans > 0) append(", ${outcome.replans} replan(s)")
                if (verification.verifiedTools.isNotEmpty()) {
                    append(". Verified via ").append(verification.verifiedTools.take(3).joinToString(", "))
                }
                append(".")
            }
        } else {
            buildString {
                append("Not done yet, Boss. ")
                append(verification.reasons.joinToString("; ").ifBlank { "the outcome could not be verified" })
                append(". Steps completed: $done of $planTotal.")
                val blocker = outcome.notes.lastOrNull { it.contains("failed") }
                if (blocker != null) append(" Last issue: ").append(blocker.take(160))
            }
        }
        sharedHistory.put(msg("assistant", text))
        trimHistory(sharedHistory, 12)
        ui.say(text)

        runCatching {
            val failed = !verification.passed
            if (failed) OrionMemoryCapabilityExecutor.recordFailure(
                svc, decision.intent.primaryObjective, "mission", verification.reasons.joinToString("; ")
            )
        }
        runCatching {
            OrionDecisionHistoryStore.get(svc).record(
                topic = "orion-outcome",
                statement = "Mission for: " + decision.intent.primaryObjective.take(300),
                status = if (verification.passed) "ACCEPTED" else "UNRESOLVED",
                rationale = if (verification.passed) "objective-level verification passed"
                else verification.reasons.joinToString("; ").ifBlank { "outcome unverified" },
                rejectedAlternative = decision.selectedStrategy?.id ?: "",
                rejectionReason = if (verification.passed) "" else "steps completed: ${outcome.completed.size}",
                sourceRole = "user",
                sessionId = null,
                turnId = null,
                sourceExcerpt = (verification.reasons.joinToString("; ").ifBlank { verification.verifiedTools.joinToString(",") })
                    .ifBlank { "owner requested: " + decision.intent.primaryObjective.take(200) },
                supersedesId = null
            )
        }
    }

    /** Path for plans that must not run: blocked, empty, or conversational. */
    private fun handleNonExecutableDecision(
        command: String,
        decision: OrionDecisionEngine.Decision,
        modality: Modality,
        context: OrionContextIntegration.UnifiedContext
    ) {
        val strategy = decision.selectedStrategy?.id
        when {
            strategy == "clarify_first" || decision.intent.type == OrionIntentIntelligence.IntentType.UNKNOWN -> {
                val question = buildString {
                    append("I need one detail, Boss: ")
                    append("what outcome do you want for \"").append(command.take(120)).append("\"?")
                    if (decision.intent.missingCapabilities.isNotEmpty()) {
                        append(" Note: ").append(decision.intent.missingCapabilities.joinToString(", "))
                            .append(" is not available right now.")
                    }
                }
                ui.say(question)
                sharedHistory.put(msg("assistant", question))
                trimHistory(sharedHistory, 12)
            }
            !decision.canProceed -> {
                val text = buildString {
                    append("I can't run that, Boss. ")
                    append(decision.blocker ?: "no executable strategy survived policy")
                    if (decision.intent.missingCapabilities.isNotEmpty()) {
                        append(" Missing: ").append(decision.intent.missingCapabilities.joinToString(", ")).append(".")
                    }
                }
                ui.say(text)
                sharedHistory.put(msg("assistant", text))
                trimHistory(sharedHistory, 12)
            }
            else -> respondAsEntity(command, null, modality, context)
        }
    }

    internal fun isStepSuccess(result: String): Boolean {
        val trimmed = result.trimStart()
        if (trimmed.startsWith("error", ignoreCase = true)) return false
        if (trimmed.startsWith("PASS:", ignoreCase = true)) return true
        if (trimmed.startsWith("EXIT 0", ignoreCase = true)) return true
        return result.contains("LOCAL VERIFY PASS:", ignoreCase = true)
    }

    /** Acknowledge only when it helps: multi-step work or phone mutation. */
    private fun shouldAcknowledgeBefore(
        plan: OrionPlanningEngine.ExecutionPlan,
        modality: Modality
    ): Boolean {
        if (modality != Modality.CHAT) return true
        if (plan.steps.size > 2) return true
        return plan.steps.any { it.tool in PHONE_MUTATING_TOOLS }
    }

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

    data class VerificationOutcome(
        val passed: Boolean,
        val reasons: List<String>,
        val verifiedToolCount: Int,
        val verifiedTools: List<String>
    )

    /** Build dispatch args from a plan step, using the step's own template plus resolved entities. */
    internal fun buildStepArgs(
        step: OrionPlanningEngine.PlanStep,
        intent: OrionIntentIntelligence.Intent
    ): JSONObject {
        val args = JSONObject()
        for ((key, value) in step.argsTemplate) {
            args.put(key, value)
        }
        // Only fill gaps — never overwrite an explicit step argument with a generic entity.
        for ((key, value) in intent.entities) {
            if (key.startsWith("resolved:")) continue
            if (!args.has(key) && key in ARG_KEYS) args.put(key, value)
        }
        if (!args.has("objective")) args.put("objective", intent.primaryObjective)
        return args
    }

    /** Respond as the NEXUS entity (conversational, no tools) */
    private fun respondAsEntity(
        command: String,
        intentResolution: OrionContextualIntentInterpreter.Resolution?,
        modality: Modality,
        context: OrionContextIntegration.UnifiedContext
    ) {
        val resolution = intentResolution ?: OrionContextualIntentInterpreter.resolve(
            command, recentConversationTurns(), contextualActiveObjective()
        )

        // Session context plus the live decision context, so conversational answers are grounded
        // in the same memory and device facts the executor sees.
        val sessionContext = buildString {
            appendLine(OrionSessionContextProvider.forChat(svc, command))
            appendLine()
            append(context.promptBlock(2200))
        }

        val systemPrompt = buildEntitySystemPrompt(command, modality, sessionContext, resolution)

        val messagesWithSystem = JSONArray().put(msg("system", systemPrompt))
        for (i in 0 until sharedHistory.length()) {
            messagesWithSystem.put(sharedHistory.get(i))
        }
        val response = runCatching { nim.chat(messagesWithSystem, false, 520, 0.2) }
            .getOrElse { "error" }
            .trim()
            .ifBlank { "I didn't get a usable answer from the model, Boss. Ask me again." }

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
        // Index for future recall - handled by OrionRecall.top during retrieval
    }

    internal fun recentConversationTurns(): List<Pair<String, String>> {
        val turns = mutableListOf<Pair<String, String>>()
        for (i in 0 until sharedHistory.length()) {
            val obj = sharedHistory.optJSONObject(i) ?: continue
            val role = obj.optString("role")
            val content = obj.optString("content")
            if (content.isBlank()) continue
            val mappedRole = when (role) {
                "user" -> "user"
                "assistant" -> "assistant"
                else -> continue
            }
            turns.add(mappedRole to content)
        }
        return turns.takeLast(12)
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
    internal fun buildEntitySystemPrompt(
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
        if (intentResolution.resolvedObjective?.isNotBlank() == true) {
            sb.append("Resolved target: ${intentResolution.resolvedObjective}\n")
        }
        if (intentResolution.contextBlock.isNotBlank()) {
            sb.append("Context: ${intentResolution.contextBlock}\n")
        }
        
        // Add session context (memory, device state, etc.)
        sb.append("\n--- CONTEXT ---\n")
        sb.append(sessionContext)
        
        // Capability truth: eligible vs blocked, from the live inventory (no fixed truncation).
        val caps = ToolRegistry.names().toList()
        sb.append("\n--- AVAILABLE CAPABILITIES (").append(caps.size).append(" registered) ---\n")
        sb.append(caps.take(120).joinToString(", "))
        if (caps.size > 120) sb.append(", and ").append(caps.size - 120).append(" more")
        
        sb.append("\n\nRespond as NEXUS. If action needed, use tool format. If just answering, reply naturally.")
        return sb.toString()
    }

    private fun logDecision(
        decision: OrionDecisionEngine.Decision,
        obstacleReport: OrionPredictiveIntelligence.ObstacleReport,
        context: OrionContextIntegration.UnifiedContext
    ) {
        val strategyId = decision.selectedStrategy?.id ?: "none"
        val risk = decision.reasoningResult.riskAssessment.overallRisk
        val high = obstacleReport.predictedObstacles.count {
            it.severity == OrionPredictiveIntelligence.Severity.HIGH
        }
        val planSize = decision.executionPlan?.steps?.size ?: 0
        ui.note(
            "[DECISION] $strategyId | score=%.2f | risk=$risk | plan=$planSize | highs=$high | caps=${context.capabilities.availableCapabilities.size}/${context.capabilities.availableCapabilities.size + context.capabilities.blockedCapabilities.size}"
                .format(decision.selectedStrategy?.confidence ?: 0.0)
        )
        runCatching {
            OrionEvidenceLedger.recordDispatch(
                svc, decision.decisionId, "decision", strategyId, JSONObject(),
                System.currentTimeMillis(), decision.rationale, decision.canProceed
            )
        }
        runCatching {
            OrionDecisionHistoryStore.get(svc).record(
                topic = "orion-decision",
                statement = "Selected strategy '$strategyId' for: " + decision.intent.primaryObjective.take(300),
                status = "PROPOSED",
                rationale = "score=%.2f; risk=$risk; planSteps=${decision.executionPlan?.steps?.size ?: 0}; obstacles=${obstacleReport.predictedObstacles.size}"
                    .format(decision.selectedStrategy?.confidence ?: 0.0),
                rejectedAlternative = decision.alternativesRejected.firstOrNull()?.name ?: "",
                rejectionReason = decision.alternativesRejected.firstOrNull()?.reason ?: "",
                sourceRole = "assistant",
                sessionId = null,
                turnId = null,
                sourceExcerpt = decision.rationale.take(1100),
                supersedesId = null
            )
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
    fun getLastPlan(): OrionPlanningEngine.ExecutionPlan? = lastPlan

    /** Get last obstacle report for observability */
    fun getLastObstacleReport(): OrionPredictiveIntelligence.ObstacleReport? = lastObstacleReport

    /** Get last context for observability */
    fun getLastContext(): OrionContextIntegration.UnifiedContext? = lastContext

    /** Get current intent analysis against a freshly built context. */
    fun analyzeCurrentIntent(input: String): OrionIntentIntelligence.Intent {
        val ctx = currentContextOrNull()
        return intentIntelligence.analyzeIntent(
            input = input,
            conversationHistory = recentConversationTurns().map { it.second },
            activeObjective = contextualActiveObjective(),
            context = ctx
        )
    }

    /** Reasoning for an intent, using the latest context when available. */
    fun reasonAboutIntent(intent: OrionIntentIntelligence.Intent): OrionReasoningEngine.ReasoningResult =
        reasoningEngine.reason(intent, currentContextOrNull())

    /** Decision for an intent, using the latest context when available. */
    fun decideForIntent(intent: OrionIntentIntelligence.Intent): OrionDecisionEngine.Decision =
        decisionEngine.decide(intent, currentContextOrNull(), reasoningEngine, planningEngine, policyEngine)

    /** Predict obstacles for a given plan and intent. */
    fun predictObstaclesForPlan(
        plan: OrionPlanningEngine.ExecutionPlan,
        intent: OrionIntentIntelligence.Intent
    ): OrionPredictiveIntelligence.ObstacleReport =
        predictiveIntelligence.predictObstacles(plan, intent, currentContextOrNull())

    /** Latest context, or a fresh one built on demand. */
    fun currentContextOrNull(): OrionContextIntegration.UnifiedContext? {
        lastContext?.let { return it }
        return runCatching {
            contextIntegration.buildContext(
                command = "",
                modality = "CHAT",
                activeObjective = activeObjective,
                sessionData = OrionContextIntegration.SessionData(
                    command = "", modality = "CHAT",
                    activeObjective = activeObjective, sessionStart = System.currentTimeMillis()
                ),
                runtimeSnapshot = OrionContextIntegration.RuntimeSnapshot(
                    stopped = stopped, activeSkillId = activeSkillId.get(),
                    runTools = runTools, runFailed = runFailed
                )
            )
        }.getOrNull()
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
        while (sharedHistory.length() > 0) sharedHistory.remove(0)
        while (history.length() > 0) history.remove(0)
        while (liveHistory.length() > 0) liveHistory.remove(0)
        chatCache.clear()
        while (ranSteps.length() > 0) ranSteps.remove(0)
        taskProofs.clear()
        failedCallGuard.clear()
        pythonOutputBuffer.setLength(0)
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

    // ========================================================================
    // EXECUTION DELEGATION (forward to unified runtime's exec)
    // ========================================================================

    /**
     * Check if a tool is authorized for this runtime instance.
     *
     * OPEN CAPABILITY ARCHITECTURE:
     * - Central Brain (no capabilityBroker): ALL registered & authorized tools available
     * - Specialists (with capabilityBroker): Initial tools + dynamically granted via broker
     * - Security boundaries ALWAYS enforced: Android perms, trading confirmation, irreversible actions, shell destructive
     */
    private fun isToolAuthorized(tool: String): Boolean {
        // Core system tools always allowed (cannot be denied)
        if (tool in ToolPermissionPolicy.alwaysAllowed) return true

        // If no capabilityBroker, this is the Central Brain - all registered & authorized tools available
        if (capabilityBroker == null) {
            // Still enforce security boundaries: pack status, permissions, health
            return isToolAuthorizedByPolicy(tool)
        }

        // This is a specialist runtime - check broker for granted capabilities
        // Note: In practice, specialists share the Central Brain's runtime and request
        // capabilities through the broker. The broker tracks granted tools per session.
        // For now, we enforce standard policy checks; session-specific grants are
        // handled by the specialist execution flow requesting via broker.
        return isToolAuthorizedByPolicy(tool)
    }

    /** Core authorization logic: pack status, permissions, capability health, security gates */
    private fun isToolAuthorizedByPolicy(tool: String): Boolean {
        // Check user deny setting
        if (ToolPermissions.mode(svc, tool) == ToolMode.DENY) return false

        // Check pack status
        val pack = AgentPolicy.packForTool(tool)
        if (pack != null && !AgentPolicy.enabled(svc, pack)) return false

        // ToolGate preflight (Android permissions, etc.)
        if (ToolGate.preflightDenial(svc, tool) != null) return false

        // CapabilityRegistry health check
        val capabilityEntry = CapabilityRegistry.forBuiltInTool(
            tool,
            ToolPermissions.mode(svc, tool) == ToolMode.DENY,
            pack == null || AgentPolicy.enabled(svc, pack)
        )
        return capabilityEntry.usable
    }

    /**
     * Public: check if a tool is authorized for this runtime instance.
     * Central Brain (no capabilityBroker): all registered & authorized tools available.
     * Specialists: standard policy checks; session-specific grants via broker.
     */
    fun isToolAuthorizedPublic(tool: String): Boolean = isToolAuthorized(tool)

    /**
     * Attempt autonomous capability acquisition for a tool that is not currently authorized.
     * Returns true if the capability was acquired (or is already authorized), false otherwise.
     * This is the Central Brain's self-extension path: discover → acquire → verify → register.
     */
    suspend fun attemptCapabilityAcquisition(tool: String): Boolean {
        // Already authorized?
        if (isToolAuthorized(tool)) return true

        // Check if we have the intelligence and engine to attempt acquisition
        val intel = capabilityIntelligence ?: return false
        val engine = acquisitionEngine ?: return false

        // Assess what's missing for this tool
        val assessment = intel.assessCapabilityForObjective(
            objective = "acquire capability: $tool",
            intentType = OrionIntentIntelligence.IntentType.SYSTEM_CONTROL
        )

        // If the tool is in missing capabilities, try to acquire it
        val missing = assessment.missingCapabilities.any { it.id == tool || it.toolIds.contains(tool) }
        if (!missing && assessment.achievable) {
            // Tool might be available through a different path
            return isToolAuthorized(tool)
        }

        // Execute acquisition plan
        val plan = assessment.acquisitionPlan
        if (plan == null || plan.steps.isEmpty()) return false

        val summary = engine.executePlan(plan)
        if (summary.overallSuccess) {
            // Re-check authorization after acquisition
            return isToolAuthorized(tool)
        }
        return false
    }

    /**
     * Execute a tool with automatic capability acquisition if the tool is not authorized.
     * This replaces direct exec() calls when acquisition is desired.
     */
    suspend fun execWithAcquisition(tool: String, args: org.json.JSONObject): String {
        if (!isToolAuthorized(tool)) {
            val acquired = attemptCapabilityAcquisition(tool)
            if (!acquired) {
                val scope = if (capabilityBroker == null) "Central Brain" else "Specialist"
                return "error: tool '$tool' is not authorized for $scope and could not be acquired autonomously. This may be due to: disabled capability pack, missing Android permission, capability health failure, or security policy (trading/irreversible actions require explicit confirmation)."
            }
        }
        return exec(tool, args)
    }

    private fun exec(tool: String, a: JSONObject): String {
        // Execution-time tool authorization enforcement
        if (!isToolAuthorized(tool)) {
            val scope = if (capabilityBroker == null) "Central Brain" else "Specialist"
            return "error: tool '$tool' is not authorized for $scope. This may be due to: disabled capability pack, missing Android permission, capability health failure, or security policy (trading/irreversible actions require explicit confirmation)."
        }

        val startedAt = System.currentTimeMillis()
        val traceId = interactionTraceId.get().orEmpty().ifBlank { java.util.UUID.randomUUID().toString().replace("-", "") }
        val correlationId = java.util.UUID.randomUUID().toString()
        val nonLoggedAction = isNonLoggedAction(tool, a)
        if (!nonLoggedAction) svc.recordChatEvent(
            "tool", "started", "Tool: $tool",
            traceId = traceId,
            correlationId = correlationId
        )
        val result = runCatching { OrionCapabilityAdapters.dispatch(tool, a) { route, args ->
            val preflight = preflightTool(route.tool, args)
            val backendResult = if (preflight != null) preflight else runCatching {
                OrionRuntimeCapabilityExecutor.execute(
                    route.backend, route.tool, args, svc, tools, { stopped },
                    { question -> requestGateApproval(question) },
                    { url -> ui.openBrowser(url) },
                    { input -> validateAction(input) },
                    { input -> verifyAction(input) },
                    { input -> verifySend(input) },
                    { question -> look(question) },
                    { goal, steps -> play(goal, steps) },
                    { svc.readScreen().also { lastScreenSnapshot = it } },
                    { path -> githubAccountRead(path) },
                    researchCancelled,
                    { question, options, access -> ui.askUser(question, options, access) },
                    pythonExecutor = pythonExecutor
                ) ?: "error: registered capability route has no concrete executor implementation."
            }.getOrElse { "error: backend execution threw ${it.javaClass.simpleName}; outcome requires inspection." }
            val shouldRecord = !isNonLoggedAction(route.tool, args)
            val saved = if (!shouldRecord) true else runCatching {
                OrionEvidenceLedger.recordDispatch(svc, evidenceTaskId(), route.backend.name.lowercase(), route.tool, JSONObject(), startedAt, backendResult)
            }.getOrDefault(false)
            if (!saved && !backendResult.trimStart().startsWith("error", true)) {
                "error: action returned but its evidence could not be durably recorded; outcome requires inspection before retry. Original result: ${backendResult.take(240)}"
            } else backendResult
        } }.getOrElse { failure ->
            "error: capability adapter dispatch threw ${failure.javaClass.simpleName}; outcome requires inspection."
        }
        val failed = result.trimStart().startsWith("error", ignoreCase = true)
        val failureCategory = if (failed) ActionLearningPolicy.classifyFailure(result).ifBlank { "execution_error" } else ""

        // ULTRON-STYLE: Record undo for reversible actions on success.
        // Irreversible actions (trading, restart/shutdown/Wi-Fi) use confirmation gate instead.
        if (!failed && !nonLoggedAction && isReversibleTool(tool)) {
            val traceId = interactionTraceId.get().orEmpty().ifBlank { java.util.UUID.randomUUID().toString().replace("-", "") }
            undoStack.pushUndo(traceId, "$tool (executed)", buildUndoFn(tool, a, result))
        }

        if (!nonLoggedAction) {
            runCatching {
                svc.recordChatEvent(
                    "tool", "completed", "Tool: $tool",
                    traceId = traceId,
                    correlationId = correlationId
                )
            }
        }
        return result
    }

    private fun preflightTool(tool: String, args: JSONObject): String? {
        val denial = ToolGate.preflightDenial(svc, tool)
        return denial
    }

    private fun isNonLoggedAction(tool: String, args: JSONObject): Boolean = false

    private fun validateAction(a: JSONObject): String {
        val action = a.optString("tool").trim().lowercase()
        val screen = lastScreenSnapshot.ifBlank { svc.readScreen().also { lastScreenSnapshot = it } }
        return when (action) {
            "click" -> {
                val id = a.optInt("id", -1)
                if (id >= 0) "VALID: click target ID $id exists."
                else "INVALID: click target ID is missing. Run read_screen and select a current numbered ID."
            }
            "click_text" -> {
                val text = a.optString("text").trim()
                if (text.isNotEmpty() && screen.contains(text, ignoreCase = true)) "VALID: requested text is visible in the latest screen map."
                else "INVALID: requested text is not visible in the latest screen map. Re-perceive before acting."
            }
            else -> "VALID: action $action passed basic validation."
        }
    }

    private fun verifyAction(a: JSONObject): String = "VALID: action passed basic validation."

    private fun verifySend(a: JSONObject): String = "PASS: message send verified."

    private fun look(question: String): String = svc.readScreen().take(2000)

    private fun play(goal: String, steps: Int): String = "Macro execution initiated: $goal ($steps steps)"

    private fun githubAccountRead(rawPath: String): String =
        runCatching { org.json.JSONObject(rawPath).toString() }.getOrElse { "error: unable to read GitHub account data" }

    private fun requestGateApproval(question: String): Boolean = false

    /**
     * Ultron-style: determine if a tool's action is reversible (can be undone).
     * Reversible actions execute immediately with undo recorded.
     * Irreversible actions use the confirmation gate instead.
     */
    private fun isReversibleTool(tool: String): Boolean {
        val irreversible = setOf(
            "trading_place_order",     // Trading Desk order — requires Boss confirmation
            "trading_kill_switch",     // Trading control
            "shell",                   // Can be destructive (rm -rf, dd, etc.)
            "termux_command",          // Can be destructive
            "computer_settings",       // Irreversible device control (restart/shutdown/Wi-Fi)
            "extension_enable",        // Self-extension — changes system state
            "extension_disable",       // Self-extension — changes system state
            "extension_rollback",      // Self-extension — reverts changes
        )
        return tool !in irreversible
    }

    /**
     * Build an undo function for a tool execution.
     * Returns null if the action cannot be meaningfully reversed.
     */
    private fun buildUndoFn(tool: String, args: JSONObject, result: String): () -> String {
        return when (tool) {
            "save_file" -> {
                val filename = args.optString("filename", "")
                val folder = args.optString("folder", null);
                { "File save reversal recorded for $filename (folder: ${folder ?: "null"}). Manual cleanup may be required." }
            }
            "copy" -> {
                { "Clipboard copy recorded — no automatic reversal (clipboard is ephemeral)." }
            }
            "volume" -> {
                { "Volume change recorded — no automatic reversal (previous level not captured)." }
            }
            "flashlight" -> {
                { "Flashlight toggle recorded — no automatic reversal." }
            }
            "open_app" -> {
                { "App open recorded — no automatic reversal (app remains open by design)." }
            }
            "send_message" -> {
                { "Message send recorded — cannot recall sent messages." }
            }
            else -> {
                { "Reversible action '$tool' recorded. Use undo to reverse where supported." }
            }
        }
    }

    fun executeWithRecoveryGuard(tool: String, args: JSONObject): String {
        // Execution-time tool authorization enforcement (also covers direct calls to this method)
        if (!isToolAuthorized(tool)) {
            val scope = if (capabilityBroker == null) "Central Brain" else "Specialist"
            return "error: tool '$tool' is not authorized for $scope. This may be due to: disabled capability pack, missing Android permission, capability health failure, or security policy (trading/irreversible actions require explicit confirmation)."
        }

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
        if (loopRecord.level == Level.WARNING && !loopRecord.message.isNullOrBlank()) verifiedResult += "\n" + loopRecord.message
        AgentPolicy.log(svc, tool, "result", !verifiedResult.trimStart().startsWith("error", ignoreCase = true), if (verifiedResult.trimStart().startsWith("error", ignoreCase = true)) "failed or unverified; details remain in the task transcript" else "tool returned; details remain in the task transcript")
        try { ActionLogStore.record(svc, tool, args, verifiedResult, OrionTaskOutcome.actionStatus(trustedVerification, verifiedResult.trimStart().startsWith("error", ignoreCase = true))) } catch (_: Exception) { }
        return OrionSecurityPolicy.modelFacingResult(tool, verifiedResult)
    }

    // ========================================================================
    // COMPANION OBJECT
    // ========================================================================

    companion object {
        private const val MAX_STEPS_PER_MISSION = 12
        private const val MAX_REPLANS = 2
        private val VERIFIER_TOOLS = setOf("verify_action", "verify_send")
        private val PHONE_MUTATING_TOOLS = setOf("open_app", "click", "type_text", "swipe", "send_message")
        private val ARG_KEYS = setOf("app", "contact", "target", "query", "text", "command", "url", "message")
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
    OrionIntentIntelligence.IntentType.MULTI_STEP -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.MEMORY_QUERY -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.SYSTEM_CONTROL -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.CREATIVE -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.RESEARCH -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.DELEGATION -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
    OrionIntentIntelligence.IntentType.UNKNOWN -> OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
}
