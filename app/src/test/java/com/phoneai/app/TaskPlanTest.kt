package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class TaskPlanTest {
    private fun plan() = TaskPlan.create("Ship the fix", listOf("Read the code", "Write the test", "Open the PR"))!!

    @Test fun createRefusesEmptyBlankAndTooLongPlans() {
        assertNull(TaskPlan.create("", listOf("a")))
        assertNull(TaskPlan.create("g", emptyList()))
        assertNull(TaskPlan.create("g", listOf("a", " ")))
        assertNull(TaskPlan.create("g", List(TaskPlan.MAX_STEPS + 1) { "s$it" }))
    }

    @Test fun stepsRunInOrderOneAtATime() {
        var p = plan()
        p = p.start("s1")!!
        assertNull("second step cannot start while one runs", p.start("s2"))
        p = p.finish("s1", "done")!!
        p = p.start("s2")!!
        assertEquals("s2", p.running?.id)
        assertEquals("s3", p.nextPending?.id)
    }

    @Test fun finishAndFailNeedARunningStep() {
        val p = plan()
        assertNull(p.finish("s1"))
        assertNull(p.fail("s1", "x"))
        val failed = p.start("s1")!!.fail("s1", "tool error")!!
        assertEquals(TaskPlan.Status.FAILED, failed.steps[0].status)
        assertEquals("tool error", failed.steps[0].note)
    }

    @Test fun finishedStepsCannotBeReopened() {
        val p = plan().start("s1")!!.finish("s1")!!
        assertNull(p.start("s1"))
        assertNull(p.skip("s1"))
    }

    @Test fun skipOnlyWhenNotStarted() {
        val p = plan()
        val skipped = p.skip("s2", "not needed")!!
        assertEquals(TaskPlan.Status.SKIPPED, skipped.steps[1].status)
        assertNull(p.start("s1")!!.skip("s1"))
    }

    @Test fun unknownIdsAreRefused() {
        assertNull(plan().start("nope"))
        assertNull(plan().skip("nope"))
    }

    @Test fun finishesWhenEveryStepIsDoneOrSkipped() {
        var p = plan().skip("s1")!!
        p = p.start("s2")!!.finish("s2")!!
        assertFalse(p.isFinished)
        p = p.start("s3")!!.finish("s3")!!
        assertTrue(p.isFinished)
        assertEquals("3 of 3 steps done", p.progressLabel())
    }

    @Test fun progressCountsFailures() {
        val p = plan().start("s1")!!.fail("s1", "boom")!!
        assertEquals("0 of 3 steps done, 1 failed", p.progressLabel())
    }

    @Test fun cardShowsEveryStepWithAMark() {
        val card = plan().start("s1")!!.finish("s1", "ok")!!.start("s2")!!.card()
        assertTrue(card.startsWith("Plan: Ship the fix"))
        assertTrue(card.contains("[x] Read the code — ok"))
        assertTrue(card.contains("[>] Write the test"))
        assertTrue(card.contains("[ ] Open the PR"))
    }

    @Test fun jsonRoundTripKeepsStatusAndNotes() {
        val p = plan().start("s1")!!.fail("s1", "why")!!
        val back = TaskPlan.fromJson(p.toJson())!!
        assertEquals(p, back)
    }

    @Test fun malformedJsonReturnsNullInsteadOfThrowing() {
        assertNull(TaskPlan.fromJson("not json"))
        assertNull(TaskPlan.fromJson("""{"goal":"g","steps":[]}"""))
        assertNull(TaskPlan.fromJson("""{"goal":"g","steps":[{"id":"s1","title":"a","status":"BOGUS"}]}"""))
        assertNull(TaskPlan.fromJson("""{"goal":"g","steps":[{"id":"s1","title":"a","status":"DONE"},{"id":"s1","title":"b","status":"DONE"}]}"""))
    }
}
