package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionGoalUnderstandingTest {
    @Test fun greetingStaysConversationalAndDoesNotTriggerTools() {
        val goal = OrionGoalUnderstanding.understand("wassup")
        assertEquals(OrionGoalUnderstanding.Kind.CONVERSATION, goal.kind)
        assertFalse(goal.shouldAct)
        assertFalse(goal.needsClarification)
    }

    @Test fun githubConnectionRequestBecomesAnOutcomeAndVerificationPlan() {
        val goal = OrionGoalUnderstanding.understand("I want to connect my GitHub account")
        assertEquals(OrionGoalUnderstanding.Kind.ACCOUNT_CONNECT, goal.kind)
        assertEquals("GitHub", goal.target)
        assertTrue(goal.shouldAct)
        assertFalse(goal.needsClarification)
        assertTrue(goal.verification.contains("authenticated status"))
    }

    @Test fun emailReadingIsAccountDataNotGenericChat() {
        val goal = OrionGoalUnderstanding.understand("read my Gmail messages")
        assertEquals(OrionGoalUnderstanding.Kind.ACCOUNT_DATA, goal.kind)
        assertTrue(goal.verification.contains("returned data"))
    }

    @Test fun terminalRequestChoosesShellExecutionAndChecksOutput() {
        val goal = OrionGoalUnderstanding.understand("use the terminal to execute this command")
        assertEquals(OrionGoalUnderstanding.Kind.SHELL_EXECUTION, goal.kind)
        assertTrue(goal.verification.contains("exit status"))
    }

    @Test fun codingRequestRequiresTestsAndDoesNotOverclaimBuild() {
        val goal = OrionGoalUnderstanding.understand("fix the build and test the code")
        assertEquals(OrionGoalUnderstanding.Kind.CODING, goal.kind)
        assertTrue(goal.verification.contains("test/build results"))
        assertTrue(goal.verification.contains("installed release"))
    }

    @Test fun shortFollowUpUsesActiveConversationInsteadOfAskingAgain() {
        val goal = OrionGoalUnderstanding.understand("continue")
        assertEquals(OrionGoalUnderstanding.Kind.GENERAL_TASK, goal.kind)
        assertTrue(goal.shouldAct)
        assertFalse(goal.needsClarification)
        assertTrue(goal.requestedOutcome.contains("active task"))
    }

    @Test fun genuinelyMissingAccountTargetRequestsClarification() {
        val goal = OrionGoalUnderstanding.understand("connect my account")
        assertEquals(OrionGoalUnderstanding.Kind.AMBIGUOUS, goal.kind)
        assertTrue(goal.needsClarification)
        assertFalse(goal.shouldAct)
    }

    @Test fun promptSummaryDoesNotEmbedRawUserInput() {
        val line = OrionGoalUnderstanding.promptLine("connect my GitHub account")
        assertTrue(line.contains("ACCOUNT_CONNECT"))
        assertTrue(line.contains("verify"))
        assertFalse(line.contains("connect my GitHub account"))
    }
}
