package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionCentralRuntimeTest {
    private fun buildEngine(step: OrionCentralRuntime.Step,
                            execute: () -> OrionCentralRuntime.StepResult,
                            verify: (OrionCentralRuntime.StepResult) -> OrionCentralRuntime.Verification,
                            blocked: String? = null): OrionCentralRuntime.Engine {
        val objective = OrionCentralRuntime.Objective(title = "test", request = "test", steps = listOf(step))
        val context = object : OrionCentralRuntime.ContextResolver {
            override fun resolve(turn: OrionCentralRuntime.Turn) =
                OrionCentralRuntime.Context(turn, turn.conversation, emptyList(), turn.activeObjective, turn.observations)
        }
        val decision = object : OrionCentralRuntime.DecisionEngine {
            override fun decide(context: OrionCentralRuntime.Context, capabilities: List<OrionCentralRuntime.Capability>) =
                OrionCentralRuntime.Decision(OrionCentralRuntime.DecisionKind.TASK, objective = objective)
            override fun replan(context: OrionCentralRuntime.Context, objective: OrionCentralRuntime.Objective,
                                evidence: List<OrionCentralRuntime.Evidence>, capabilities: List<OrionCentralRuntime.Capability>) = objective
        }
        val capability = OrionCentralRuntime.Capability(step.capability, "test")
        val registry = object : OrionCentralRuntime.CapabilityRegistry {
            override fun all() = listOf(capability)
            override fun find(name: String) = capability.takeIf { it.name == name }
        }
        val policy = object : OrionCentralRuntime.ActionPolicy {
            override fun blockReason(capability: OrionCentralRuntime.Capability, step: OrionCentralRuntime.Step,
                                      context: OrionCentralRuntime.Context) = blocked
        }
        val executor = object : OrionCentralRuntime.Executor {
            override fun execute(capability: OrionCentralRuntime.Capability, step: OrionCentralRuntime.Step) = execute()
        }
        val verifier = object : OrionCentralRuntime.OutcomeVerifier {
            override fun verify(step: OrionCentralRuntime.Step, result: OrionCentralRuntime.StepResult) = verify(result)
        }
        val memory = object : OrionCentralRuntime.MemoryWriter {
            override fun observe(context: OrionCentralRuntime.Context, receipt: OrionCentralRuntime.Receipt) = Unit
        }
        val events = object : OrionCentralRuntime.EventSink {
            override fun onPhase(turnId: String, phase: OrionCentralRuntime.Phase, detail: String) = Unit
        }
        return OrionCentralRuntime.Engine(context, decision, registry, policy, executor, verifier, memory, events)
    }

    @Test fun taskCompletesOnlyAfterIndependentVerification() {
        val step = OrionCentralRuntime.Step(objective = "build", capability = "build", successCondition = "tests pass")
        val engine = buildEngine(step, { OrionCentralRuntime.StepResult("BUILD SUCCESS", true, "gradle") },
            { OrionCentralRuntime.Verification(true, "tests passed") })
        val receipt = engine.run(OrionCentralRuntime.Turn(input = "build"))
        assertEquals(OrionCentralRuntime.Phase.COMPLETED, receipt.phase)
        assertTrue(receipt.evidence.single().verified)
    }

    @Test fun commandSuccessWithoutVerificationDoesNotCompleteTask() {
        val step = OrionCentralRuntime.Step(objective = "deploy", capability = "deploy", successCondition = "health check passes")
        val engine = buildEngine(step, { OrionCentralRuntime.StepResult("exit 0", true, "shell") },
            { OrionCentralRuntime.Verification(false, "health check failed") })
        val receipt = engine.run(OrionCentralRuntime.Turn(input = "deploy"))
        assertEquals(OrionCentralRuntime.Phase.FAILED, receipt.phase)
        assertFalse(receipt.evidence.single().verified)
    }

    @Test fun policyBlockPreventsExecution() {
        val step = OrionCentralRuntime.Step(objective = "send", capability = "send", successCondition = "sent")
        var invoked = false
        val engine = buildEngine(step, { invoked = true; OrionCentralRuntime.StepResult("sent", true, "test") },
            { OrionCentralRuntime.Verification(true, "ok") }, blocked = "Approval required")
        val receipt = engine.run(OrionCentralRuntime.Turn(input = "send"))
        assertFalse(invoked)
        assertEquals("action_blocked", receipt.error)
    }
}
