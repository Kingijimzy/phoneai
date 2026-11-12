package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskReminderPolicyTest {
    @Test fun completedAndCancelledTasksAreTerminal() {
        assertTrue(TaskReminderPolicy.isTerminal("completed"))
        assertTrue(TaskReminderPolicy.isTerminal("done"))
        assertTrue(TaskReminderPolicy.isTerminal("cancelled"))
        assertTrue(TaskReminderPolicy.isTerminal("canceled"))
        assertTrue(TaskReminderPolicy.isTerminal("failed"))
    }
    @Test fun activeTaskStatesRemainReminderEligible() {
        assertFalse(TaskReminderPolicy.isTerminal("queued"))
        assertFalse(TaskReminderPolicy.isTerminal("running"))
        assertFalse(TaskReminderPolicy.isTerminal("paused"))
    }
}
