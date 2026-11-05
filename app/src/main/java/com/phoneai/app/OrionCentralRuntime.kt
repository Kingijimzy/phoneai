package com.phoneai.app

import java.util.UUID

/**
 * Single orchestration kernel adapted from the primary-runtime/action-registry design in
 * Kingijimzy/Ultron. Android capabilities remain native Kotlin handlers.
 *
 * Production adapters are supplied by Agent; this kernel is platform-neutral and testable.
 */
internal object OrionCentralRuntime {
    enum class Phase { IDLE, PERCEIVING, CONTEXTUALIZING, DECIDING, PLANNING, EXECUTING, VERIFYING, RECOVERING, COMPLETED, FAILED }
    enum class DecisionKind { CONVERSATION, TASK, CLARIFICATION, UNSUPPORTED }
    enum class SideEffect { READ, WRITE, EXTERNAL, DESTRUCTIVE }
    enum class ObjectiveStatus { ACTIVE, COMPLETED, FAILED }

    data class Turn(val id: String = UUID.randomUUID().toString(), val input: String,
                    val conversation: List<Pair<String, String>> = emptyList(),
                    val activeObjective: Objective? = null, val observations: Map<String, String> = emptyMap())
    data class MemoryFact(val subject: String, val relation: String, val value: String,
                          val source: String, val confidence: Double, val observedAtEpochMs: Long,
                          val status: String = "current")
    data class Context(val turn: Turn, val relevantHistory: List<Pair<String, String>>,
                       val memories: List<MemoryFact>, val objective: Objective?,
                       val observations: Map<String, String>, val previousEvidence: List<Evidence> = emptyList())
    data class Capability(val name: String, val description: String, val inputSchema: String = "{}",
                          val sideEffect: SideEffect = SideEffect.READ, val prerequisites: Set<String> = emptySet(),
                          val platform: String = "android", val enabled: Boolean = true)
    data class Step(val id: String = UUID.randomUUID().toString(), val objective: String,
                    val capability: String, val arguments: Map<String, String> = emptyMap(),
                    val dependsOn: Set<String> = emptySet(), val successCondition: String)
    data class Objective(val id: String = UUID.randomUUID().toString(), val title: String,
                         val request: String, val status: ObjectiveStatus = ObjectiveStatus.ACTIVE,
                         val steps: List<Step> = emptyList(), val completedStepIds: Set<String> = emptySet())
    data class Decision(val kind: DecisionKind, val response: String = "",
                        val objective: Objective? = null, val clarification: String? = null)
    data class StepResult(val output: String, val succeeded: Boolean, val evidenceSource: String)
    data class Verification(val passed: Boolean, val explanation: String)
    data class Evidence(val stepId: String, val capability: String, val output: String,
                        val succeeded: Boolean, val verified: Boolean, val source: String)
    data class Receipt(val turnId: String, val phase: Phase, val response: String,
                       val objective: Objective?, val evidence: List<Evidence>, val error: String? = null)

    interface ContextResolver { fun resolve(turn: Turn): Context }
    interface DecisionEngine {
        fun decide(context: Context, capabilities: List<Capability>): Decision
        fun replan(context: Context, objective: Objective, evidence: List<Evidence>, capabilities: List<Capability>): Objective
    }
    interface CapabilityRegistry { fun all(): List<Capability>; fun find(name: String): Capability? }
    interface ActionPolicy { fun blockReason(capability: Capability, step: Step, context: Context): String? }
    interface Executor { fun execute(capability: Capability, step: Step): StepResult }
    interface OutcomeVerifier { fun verify(step: Step, result: StepResult): Verification }
    interface MemoryWriter { fun observe(context: Context, receipt: Receipt) }
    interface EventSink { fun onPhase(turnId: String, phase: Phase, detail: String) }

    class Engine(private val contexts: ContextResolver, private val decisions: DecisionEngine,
                 private val registry: CapabilityRegistry, private val policy: ActionPolicy,
                 private val executor: Executor, private val verifier: OutcomeVerifier,
                 private val memory: MemoryWriter, private val events: EventSink,
                 private val maxSteps: Int = 24, private val maxReplans: Int = 2) {
        fun run(turn: Turn): Receipt {
            val evidence = mutableListOf<Evidence>()
            var objective = turn.activeObjective
            fun phase(value: Phase, detail: String) = events.onPhase(turn.id, value, detail)
            fun finish(value: Phase, response: String, error: String? = null): Receipt {
                phase(value, response.take(240))
                val receipt = Receipt(turn.id, value, response, objective, evidence.toList(), error)
                runCatching { memory.observe(contexts.resolve(turn), receipt) }
                return receipt
            }
            return try {
                phase(Phase.PERCEIVING, "Collecting request, transcript, objective and device state")
                var context = contexts.resolve(turn)
                phase(Phase.CONTEXTUALIZING, "Resolving history, memory and prior evidence")
                val capabilities = registry.all().filter { it.enabled }
                phase(Phase.DECIDING, "Choosing conversation, clarification or task")
                val decision = decisions.decide(context, capabilities)
                when (decision.kind) {
                    DecisionKind.CONVERSATION -> return finish(Phase.COMPLETED, decision.response)
                    DecisionKind.CLARIFICATION -> return finish(Phase.COMPLETED, decision.clarification ?: decision.response)
                    DecisionKind.UNSUPPORTED -> return finish(Phase.FAILED, decision.response.ifBlank { "Capability unavailable." }, "unsupported")
                    DecisionKind.TASK -> objective = decision.objective ?: objective
                }
                var task = objective ?: return finish(Phase.FAILED, "Task decision did not include an objective.", "missing_objective")
                var ran = 0
                var replans = 0
                val attempted = mutableSetOf<String>()
                while (task.status == ObjectiveStatus.ACTIVE && ran < maxSteps) {
                    val pending = task.steps.filter { it.id !in task.completedStepIds }
                    if (pending.isEmpty()) {
                        task = task.copy(status = ObjectiveStatus.COMPLETED)
                        objective = task
                        return finish(Phase.COMPLETED, "Objective completed; verified steps: " + evidence.count { it.verified })
                    }
                    val step = pending.firstOrNull { task.completedStepIds.containsAll(it.dependsOn) }
                        ?: return finish(Phase.FAILED, "Plan has unresolved or cyclic dependencies.", "dependency_deadlock")
                    val capability = registry.find(step.capability)
                        ?: return finish(Phase.FAILED, "Required capability unavailable: " + step.capability, "missing_capability")
                    val fingerprint = step.capability + ":" + step.arguments.toSortedMap()
                    if (!attempted.add(fingerprint)) {
                        if (replans >= maxReplans) return finish(Phase.FAILED, "Repeated action prevented safe recovery.", "repeated_action")
                        replans++
                        phase(Phase.RECOVERING, "Requesting a revised plan after repeated action")
                        context = contexts.resolve(turn).copy(previousEvidence = evidence.toList())
                        task = decisions.replan(context, task, evidence.toList(), capabilities)
                        objective = task
                        continue
                    }
                    val blocked = policy.blockReason(capability, step, context)
                    if (blocked != null) {
                        evidence += Evidence(step.id, step.capability, blocked, false, false, "action-policy")
                        return finish(Phase.FAILED, blocked, "action_blocked")
                    }
                    phase(Phase.EXECUTING, "Executing " + capability.name)
                    val result = executor.execute(capability, step)
                    ran++
                    phase(Phase.VERIFYING, "Verifying observed result")
                    val checked = verifier.verify(step, result)
                    evidence += Evidence(step.id, step.capability, result.output,
                        result.succeeded && checked.passed, checked.passed, result.evidenceSource)
                    if (result.succeeded && checked.passed) {
                        task = task.copy(completedStepIds = task.completedStepIds + step.id)
                        objective = task
                        context = contexts.resolve(turn).copy(previousEvidence = evidence.toList())
                    } else {
                        if (replans >= maxReplans) return finish(Phase.FAILED,
                            "Verification failed: " + checked.explanation, "verification_failed")
                        replans++
                        phase(Phase.RECOVERING, "Diagnosing failure and revising remaining plan")
                        context = contexts.resolve(turn).copy(previousEvidence = evidence.toList())
                        task = decisions.replan(context, task, evidence.toList(), capabilities)
                        objective = task
                    }
                }
                finish(Phase.FAILED, "Execution step limit reached or objective incomplete.", "step_limit")
            } catch (failure: Throwable) {
                finish(Phase.FAILED, "Runtime error: " + (failure.message ?: failure.javaClass.simpleName),
                    failure.javaClass.simpleName)
            }
        }
    }
}
