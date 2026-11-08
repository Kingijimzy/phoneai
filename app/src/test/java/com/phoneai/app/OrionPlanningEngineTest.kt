package com.phoneai.app

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for OrionPlanningEngine - generating executable objective graphs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionPlanningEngineTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testPlanStepStatuses() {
        val statuses = OrionPlanningEngine.PlanStepStatus.values()
        assertTrue("Should have multiple step statuses", statuses.size >= 6)
        assertTrue(statuses.contains(OrionPlanningEngine.PlanStepStatus.PENDING))
        assertTrue(statuses.contains(OrionPlanningEngine.PlanStepStatus.READY))
        assertTrue(statuses.contains(OrionPlanningEngine.PlanStepStatus.RUNNING))
        assertTrue(statuses.contains(OrionPlanningEngine.PlanStepStatus.COMPLETE))
        assertTrue(statuses.contains(OrionPlanningEngine.PlanStepStatus.FAILED))
        assertTrue(statuses.contains(OrionPlanningEngine.PlanStepStatus.BLOCKED))
    }

    @Test
    fun testPlanStepDataClass() {
        val step = OrionPlanningEngine.PlanStep(
            stepId = "step_1",
            name = "Open WhatsApp",
            description = "Open WhatsApp application",
            tool = "open_app",
            argsTemplate = mapOf("name" to "WhatsApp"),
            verificationMethod = "SCREEN_CHANGE",
            expectedEvidence = "WhatsApp opened",
            dependsOn = emptyList()
        )
        assertEquals("step_1", step.stepId)
        assertEquals("open_app", step.tool)
        assertEquals(OrionPlanningEngine.PlanStepStatus.PENDING, step.status)
    }

    @Test
    fun testExecutionPlanCreation() {
        val step1 = OrionPlanningEngine.PlanStep(
            stepId = "step_1", name = "Step 1", description = "First step",
            tool = "open_app", argsTemplate = emptyMap(),
            verificationMethod = "SCREEN_CHANGE", expectedEvidence = "Done",
            dependsOn = emptyList()
        )
        val step2 = OrionPlanningEngine.PlanStep(
            stepId = "step_2", name = "Step 2", description = "Second step",
            tool = "read_screen", argsTemplate = emptyMap(),
            verificationMethod = "SCREEN_CHANGE", expectedEvidence = "Done",
            dependsOn = listOf("step_1")
        )
        val plan = OrionPlanningEngine.ExecutionPlan(
            planId = "plan_1",
            objective = "Test objective",
            steps = listOf(step1, step2),
            dependencies = mapOf("step_1" to emptyList(), "step_2" to listOf("step_1")),
            successConditions = listOf("Both steps complete"),
            estimatedTotalDurationMs = 10000,
            rollbackPlan = null,
            createdAt = System.currentTimeMillis()
        )
        assertEquals("plan_1", plan.planId)
        assertEquals("Test objective", plan.objective)
        assertEquals(2, plan.steps.size)
        val ordered = plan.orderedSteps()
        assertEquals(2, ordered.size)
        assertEquals("step_1", ordered[0].stepId)
        assertEquals("step_2", ordered[1].stepId)
    }

    @Test
    fun testRollbackPlan() {
        val rollbackStep = OrionPlanningEngine.PlanStep(
            stepId = "rollback_1",
            name = "Rollback step",
            description = "Undo previous action",
            tool = "file_delete",
            argsTemplate = emptyMap(),
            verificationMethod = "FILE_NOT_EXISTS",
            expectedEvidence = "File deleted"
        )
        val rollback = OrionPlanningEngine.RollbackPlan(
            triggerConditions = listOf("verification_failed", "user_cancelled"),
            steps = listOf(rollbackStep)
        )
        assertEquals(2, rollback.triggerConditions.size)
        assertEquals(1, rollback.steps.size)
        assertEquals("rollback_1", rollback.steps[0].stepId)
    }

    @Test
    fun testReplanFromFailure() {
        val step1 = OrionPlanningEngine.PlanStep(
            stepId = "step_1", name = "Step 1", description = "First",
            tool = "open_app", argsTemplate = emptyMap(),
            verificationMethod = "SCREEN_CHANGE", expectedEvidence = "Done"
        )
        val step2 = OrionPlanningEngine.PlanStep(
            stepId = "step_2", name = "Step 2", description = "Second",
            tool = "read_screen", argsTemplate = emptyMap(),
            verificationMethod = "SCREEN_CHANGE", expectedEvidence = "Done",
            dependsOn = listOf("step_1")
        )
        val plan = OrionPlanningEngine.ExecutionPlan(
            planId = "plan_1",
            objective = "Test",
            steps = listOf(step1, step2),
            dependencies = mapOf("step_1" to emptyList(), "step_2" to listOf("step_1")),
            successConditions = listOf("Done"),
            estimatedTotalDurationMs = 5000,
            rollbackPlan = null,
            createdAt = System.currentTimeMillis()
        )
        val replanned = plan.copy(steps = plan.steps.map { s ->
            if (s.stepId == "step_2") s.copy(status = OrionPlanningEngine.PlanStepStatus.FAILED) else s
        })
        assertEquals(OrionPlanningEngine.PlanStepStatus.FAILED, replanned.steps[1].status)
    }
}
