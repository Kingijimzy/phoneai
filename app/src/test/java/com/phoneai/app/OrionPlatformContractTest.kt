package com.phoneai.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * The platform promise, stage by stage: understand, research, remember, plan, choose tools, delegate, execute, handle
 * failure, verify, learn. This proves each stage has a real registered tool or rule behind it and that the pure parts
 * compose; it does not prove that the model uses them well. That is what real runs and `brain {action:"speed"}` are for.
 */
class OrionPlatformContractTest {
    @Before fun reset() { OrionMissionControl.clearForTest(); OrionSpeedTrace.clearForTest() }

    private val names = ToolRegistry.all.map { it.name }.toSet()
    private fun has(vararg t: String) = t.forEach { assertTrue("missing tool $it", it in names) }

    @Test fun everyStageOfTheObjectiveFlowHasARealCapability() {
        has("recall", "decision_history", "knowledge_search")                       // understand / recall
        has("research_web", "fetch_url", "live_web_search")                          // research
        has("remember", "fact_add", "update_memory")                                 // remember
        has("project_plan", "task_add", "goal_add", "dev_plan")                      // plan
        has("capability_search", "brain")                                            // choose tools / know capabilities
        has("spawn_worker", "queue_agent", "ask_agent")                              // delegate
        has("shell", "python_exec", "browser_navigate", "workspace_write", "device_operation") // execute across capabilities
        has("self_diagnose", "repair_apply")                                         // handle failure
        has("verify_action", "browser_verify", "project_verify", "complete_objective") // verify
        has("self_review")                                                           // learn
    }

    @Test fun lifecycleHasVerifyAndRecordSoNothingCompletesUnverified() {
        val phases = TaskLifecycle.Phase.values().map { it.name }
        assertTrue("VERIFY" in phases && "RECORD" in phases)
        assertNull(TaskLifecycle().advance()!!.advance()!!.advance()!!.advance()!!.advance()!!.advance())
    }

    @Test fun oneComplexObjectiveFlowsThroughTheSharedPieces() {
        val goal = "Research the top three competitors, then compare their pricing, then design a plan and verify the sheet step by step."
        // 1. effort scales to difficulty on any provider
        assertEquals(ModelRouter.Tier.DEEP, OrionDifficulty.tierFor(goal))
        // 2. the objective is one mission; delegated work is its child; stopping the objective stops the worker
        val root = OrionMissionControl.begin("chat", goal, parentId = null, mode = "FULL")
        OrionMissionControl.claim(root.id, OrionMissionControl.Owner("brain-chat", OrionMissionControl.OwnerKind.BRAIN))
        var stopped = false
        val child = OrionMissionControl.within(root.id) { OrionMissionControl.begin("worker", "research competitor pricing") }
        OrionMissionControl.claim(child.id, OrionMissionControl.Owner("w1", OrionMissionControl.OwnerKind.WORKER)) { stopped = true }
        assertEquals(root.id, child.parentId)
        // 3. a degraded provider is routed around, honestly
        val plan = OrionAdaptivePlan.plan(
            listOf(OrionAdaptivePlan.ProviderState("a", "A", true, true, "DOWN", 1), OrionAdaptivePlan.ProviderState("b", "B", true, true, "HEALTHY", 5)), emptyList())
        assertEquals("b", plan.primary)
        // 4. an earlier verified route becomes a reflex for a repeat request
        val route = "research competitor pricing compare sheet" to listOf("research_web", "workspace_write", "project_verify")
        assertNotNull(OrionMuscleMemory.pick(listOf(route, route), "research competitor pricing compare sheet", emptySet()))
        // 5. verification gates completion; unverified work is never reported as done
        assertEquals("unverified", OrionMissionControl.finish(child.id, ok = true, verified = false, evidence = "report written")!!.outcome)
        assertEquals("verified", OrionMissionControl.finish(root.id, ok = true, verified = true, evidence = "sheet read back")!!.outcome)
        // 6. what was learned is recalled next time from a short, differently worded request
        val lesson = Experience(ExperiencePolicy.gist("researching competitor pricing comparing sheet workspace verify"), "workspace_write", "worked", "", "", 1, 1L, "research_web>workspace_write>project_verify")
        assertEquals(1, ExperiencePolicy.rank(listOf(lesson), "research competitor pricing", 2L, 3).size)
        assertFalse(stopped)
    }
}
