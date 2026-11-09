package com.phoneai.app

import android.content.Context
import kotlinx.coroutines.*

/**
 * Phase 0C: Mission Ownership
 *
 * Specialist execution runtime. Two modes:
 * 1. Built-in local specialists (preserved from Phase 0A/0B)
 * 2. Dynamic specialists created by OrionAgentFactory (new)
 *
 * Dynamic specialists get scoped tools, isolated context, resource limits, and must
 * return verified receipts to their parent mission.
 */
internal class OrionSpecialistRuntime(
    private val context: Context,
    private val svc: PhoneAiService,
    private val ui: AgentUi,
    private val tools: Tools,
    private val localExecutor: Local,
    private val pythonExecutor: OrionPythonCapabilityExecutor,
    private val commandNim: Nim
) {

    // Built-in specialists (Phase 0A/0B compatibility)
    private val builtInWorkers = OrionBuiltInSpecialists(context.applicationContext).workers()

    /** Execute a dynamic specialist session with real tool dispatch. */
    suspend fun executeSpecialist(session: SpecialistSession): OrionAgentFactory.SpecialistSession {
        val startedAt = System.currentTimeMillis()
        val evidence = mutableListOf<String>()
        var toolCalls = 0
        var verdict = SpecialistVerdict.FAILED
        var result = "Specialist did not produce a result"

        // Specialist timeout: 10 minutes max
        val TIMEOUT_MS = 10 * 60 * 1000L

        try {
            withTimeout(TIMEOUT_MS) {
                session.status = SpecialistStatus.RUNNING

                // Build a scoped unified runtime for this specialist
                val specialistRuntime = buildScopedRuntime(session)

                // Execute the specialist's objective through the decision pipeline
                val intent = specialistRuntime.analyzeCurrentIntent(session.objective)
                val decision = specialistRuntime.decideForIntent(intent)

                if (!decision.canProceed || decision.executionPlan == null || decision.executionPlan!!.steps.isEmpty()) {
                    result = "Specialist cannot proceed: ${decision.blocker ?: "no executable plan"}"
                    verdict = SpecialistVerdict.FAILED
                } else {
                    // Execute the plan
                    val outcome = specialistRuntime.executePlan(
                        decision.executionPlan!!, decision,
                        specialistRuntime.predictObstaclesForPlan(decision.executionPlan!!, intent),
                        OrionUnifiedRuntime.Modality.COMMAND,
                        specialistRuntime.lastContext!!
                    )

                    // Verify
                    val verification = specialistRuntime.verifyOutcome(outcome, intent, specialistRuntime.lastContext)
                    verdict = if (verification.passed) SpecialistVerdict.VERIFIED else SpecialistVerdict.UNVERIFIED
                    result = outcome.notes.joinToString("; ")
                    evidence += outcome.notes
                    toolCalls = outcome.completed.size + outcome.failed.size
                }
            }
        } catch (e: Exception) {
            result = if (e is kotlinx.coroutines.TimeoutCancellationException) {
                "error: specialist timed out after ${TIMEOUT_MS / 60000} minutes"
            } else {
                "error: specialist execution failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            verdict = SpecialistVerdict.FAILED
        } finally {
            val completedAt = System.currentTimeMillis()
            session.status = if (verdict == SpecialistVerdict.FAILED) SpecialistStatus.FAILED else SpecialistStatus.COMPLETED
            session.result = result
            session.evidence = evidence
            session.verdict = verdict
            session.toolCalls = toolCalls
            session.completedAt = completedAt
        }

        return session
    }

    /** Build a unified runtime for this specialist using the Central Brain's full capability set.
     * Specialists are NOT tool-restricted at runtime — they use the same Central Brain runtime
     * with all registered capabilities, and security boundaries are enforced at dispatch time
     * (Android perms, trading confirmation, irreversible actions, shell destructive blocks).
     * Dynamic expansion happens via OrionCapabilityBroker.requestCapabilities. */
    private fun buildScopedRuntime(session: OrionAgentFactory.SpecialistSession): OrionUnifiedRuntime {
        // Create a runtime with the same capabilities as the Central Brain
        // No allowedTools filter — specialists expand via broker, not pre-restriction
        return OrionUnifiedRuntime(
            svc = svc,
            ui = object : AgentUi {
                override fun say(text: String) { /* specialist output goes to receipt */ }
                override fun status(text: String) { }
                override fun note(text: String) { }
                override fun phase(p: String) { }
                override fun ask(question: String): Boolean = false
                override fun askUser(question: String, options: List<String>, access: String): String = ""
                override fun openBrowser(url: String) { }
            },
            nim = commandNim,
            tools = tools,
            localExecutor = localExecutor,
            pythonExecutor = pythonExecutor,
            commandNim = commandNim,
            capabilityBroker = null, // Specialists use broker via AgentFactory path, not here
            capabilityIntelligence = null,
            acquisitionEngine = null,
            extensionStore = null,
            extensionSandbox = null
        )
    }

    // ------------------------------------------------------------------------
    // BUILT-IN SPECIALIST DISPATCH (Phase 0A/0B compatibility)
    // ------------------------------------------------------------------------

    fun getBuiltInWorkers(): List<OrionTaskWorker> = builtInWorkers

    /**
     * Dispatch a task to a built-in specialist via the distributed coordinator.
     * Returns a coordinated result with receipt.
     */
    fun dispatchBuiltIn(context: Context, taskId: String): OrionCoordinatedResult {
        val app = context.applicationContext
        val tasks = OrionDistributedTaskStore(app)
        val task = tasks.get(taskId)?.envelope
            ?: return OrionCoordinatedResult.Failed(taskId, "task_not_found")
        val workers = builtInWorkers
        val requested = task.requestedCapabilities.map { it.lowercase() }.toSet()
        val selected = when {
            "coding" in requested || task.kind == OrionTaskEnvelope.Kind.CODE -> OrionSpecialist.CODING
            "diagnostics" in requested || task.kind == OrionTaskEnvelope.Kind.DIAGNOSTICS -> OrionSpecialist.DIAGNOSTICS
            "trading_analysis" in requested || task.kind == OrionTaskEnvelope.Kind.TRADING_ANALYSIS -> OrionSpecialist.TRADING_ANALYSIS
            "verification" in requested || task.kind == OrionTaskEnvelope.Kind.VERIFICATION -> OrionSpecialist.VERIFICATION
            "research" in requested || task.kind == OrionTaskEnvelope.Kind.RESEARCH -> OrionSpecialist.RESEARCH
            else -> return OrionCoordinatedResult.NeedsOwner(taskId, "specialist_route_ambiguous; add one requested capability")
        }
        val worker = workers.firstOrNull { it.workerId == when (selected) {
            OrionSpecialist.RESEARCH -> "orion-research"
            OrionSpecialist.CODING -> "orion-coding"
            OrionSpecialist.DIAGNOSTICS -> "orion-diagnostics"
            OrionSpecialist.TRADING_ANALYSIS -> "orion-trading"
            OrionSpecialist.VERIFICATION -> "orion-verification"
        } } ?: return OrionCoordinatedResult.Failed(taskId, "specialist_not_registered")
        return OrionExecutionCoordinator(tasks, OrionWorkerLeaseStore(app), app).dispatch(taskId, worker)
    }

    /**
     * Remote LangGraph dispatch (preserved from Phase 0A/0B).
     */
    fun dispatchRemote(
        context: Context,
        taskId: String,
        endpoint: String,
        bearerToken: () -> String?
    ): OrionCoordinatedResult {
        val app = context.applicationContext
        val tasks = OrionDistributedTaskStore(app)
        val remote = OrionLangGraphRemoteWorker(
            OrionLangGraphTransport(endpoint, bearerToken),
            bearerToken,
            OrionRemoteTaskReceiptStore(app)
        )
        val worker = object : OrionTaskWorker {
            override val workerId = "orion-langgraph-remote"
            override val supportedKinds = OrionTaskEnvelope.Kind.values().toSet()
            override val supportedCapabilities = setOf("research", "coding", "diagnostics", "trading_analysis", "verification")
            override fun execute(task: OrionTaskEnvelope): OrionWorkerResult = when (
                val result = remote.execute(task) { tasks.isCancellationRequested(task.taskId) }
            ) {
                is OrionLangGraphRemoteWorker.RemoteResult -> when (result.status) {
                    "completed" -> OrionWorkerResult.Success(result.evidenceRef ?: throw IllegalStateException("remote_evidence_missing"))
                    "failed" -> OrionWorkerResult.Failure(result.detail ?: "remote_task_failed", false)
                    "cancelled", "cancel_requested" -> OrionWorkerResult.NeedsOwner(result.detail ?: "remote_task_cancelled")
                    else -> OrionWorkerResult.Failure("remote_pending_resume_from_receipt", true)
                }
                else -> OrionWorkerResult.Failure("remote_result_type_unknown", false)
            }
        }
        return OrionExecutionCoordinator(tasks, OrionWorkerLeaseStore(app), app).dispatch(taskId, worker)
    }
    companion object {
        fun dispatch(context: Context, taskId: String): OrionCoordinatedResult {
            val app = context.applicationContext
            val tasks = OrionDistributedTaskStore(app)
            val task = tasks.get(taskId)?.envelope ?: return OrionCoordinatedResult.Failed(taskId, "task_not_found")
            val requested = task.requestedCapabilities.map { it.lowercase() }.toSet()
            val workerId = when {
                "coding" in requested || task.kind == OrionTaskEnvelope.Kind.CODE -> "orion-coding"
                "diagnostics" in requested || task.kind == OrionTaskEnvelope.Kind.DIAGNOSTICS -> "orion-diagnostics"
                "trading_analysis" in requested || task.kind == OrionTaskEnvelope.Kind.TRADING_ANALYSIS -> "orion-trading"
                "verification" in requested || task.kind == OrionTaskEnvelope.Kind.VERIFICATION -> "orion-verification"
                "research" in requested || task.kind == OrionTaskEnvelope.Kind.RESEARCH -> "orion-research"
                else -> return OrionCoordinatedResult.NeedsOwner(taskId, "specialist_route_ambiguous; add one requested capability")
            }
            val worker = OrionBuiltInSpecialists(app).workers().firstOrNull { it.workerId == workerId }
                ?: return OrionCoordinatedResult.Failed(taskId, "specialist_not_registered")
            return OrionExecutionCoordinator(tasks, OrionWorkerLeaseStore(app), app).dispatch(taskId, worker)
        }
        fun dispatchRemote(context: Context, taskId: String, endpoint: String, bearerToken: () -> String?): OrionCoordinatedResult {
            val app = context.applicationContext
            val tasks = OrionDistributedTaskStore(app)
            val remote = OrionLangGraphRemoteWorker(OrionLangGraphTransport(endpoint, bearerToken), bearerToken, OrionRemoteTaskReceiptStore(app))
            val worker = object : OrionTaskWorker {
                override val workerId = "orion-langgraph-remote"
                override val supportedKinds = OrionTaskEnvelope.Kind.values().toSet()
                override val supportedCapabilities = setOf("research", "coding", "diagnostics", "trading_analysis", "verification")
                override fun execute(task: OrionTaskEnvelope): OrionWorkerResult = when (val result = remote.execute(task) { tasks.isCancellationRequested(task.taskId) }) {
                    is OrionLangGraphRemoteWorker.RemoteResult -> when (result.status) {
                        "completed" -> OrionWorkerResult.Success(result.evidenceRef ?: throw IllegalStateException("remote_evidence_missing"))
                        "failed" -> OrionWorkerResult.Failure(result.detail ?: "remote_task_failed", false)
                        "cancelled", "cancel_requested" -> OrionWorkerResult.NeedsOwner(result.detail ?: "remote_task_cancelled")
                        else -> OrionWorkerResult.Failure("remote_pending_resume_from_receipt", true)
                    }
                    else -> OrionWorkerResult.Failure("remote_result_type_unknown", false)
                }
            }
            return OrionExecutionCoordinator(tasks, OrionWorkerLeaseStore(app), app).dispatch(taskId, worker)
        }
    }
}

// SpecialistVerdict is declared in OrionMissionStatus.kt; SpecialistStatus in OrionAgentFactory.kt.
// Both are visible here via same-package resolution — no duplicate declarations.