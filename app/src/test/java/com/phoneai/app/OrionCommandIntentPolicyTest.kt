package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionCommandIntentPolicyTest {
    @Test fun explicitMultiObjectiveGraphRequestMustUseStructuredPlanning() {
        assertTrue(OrionCommandIntentPolicy.requiresStructuredTaskPlanning(
            "I have one project with four objectives. Create a structured objective graph; they depend on one another in that order."
        ))
    }

    @Test fun resumableMultiStepTaskMustUseStructuredPlanning() {
        assertTrue(OrionCommandIntentPolicy.requiresStructuredTaskPlanning(
            "Create a three-step task and save its progress so it can be resumed if the app closes."
        ))
    }

    @Test fun namedTaskWithStepsAndCheckpointUsesStructuredPlanning() {
        assertTrue(OrionCommandIntentPolicy.requiresStructuredTaskPlanning(
            "Create a task called Orion Reliability Test. Break it into these steps: check memory, check recovery, check providers. Save the task and show me its checkpoint and next unfinished action."
        ))
        assertTrue(OrionCommandIntentPolicy.requiresDirectActionRouting(
            "Create a task called Orion Reliability Test. Break it into these steps: check memory, check recovery, check providers. Save the task and show me its checkpoint and next unfinished action."
        ))
    }

    @Test fun explicitDecisionMemoryWriteSkipsTheChatOnlyClassificationCall() {
        assertTrue(OrionCommandIntentPolicy.requiresDirectActionRouting(
            "Remember this decision: reliability before adding new features. Record the reason in decision history."
        ))
    }

    @Test fun capabilityQuestionIsNotMistakenForAnAction() {
        assertFalse(OrionCommandIntentPolicy.requiresDirectActionRouting(
            "List the phone actions and memory tools you can actually execute right now."
        ))
    }

    @Test fun ordinaryThreeStepExplanationIsNotForcedIntoTaskExecution() {
        assertFalse(OrionCommandIntentPolicy.requiresStructuredTaskPlanning(
            "Explain three steps for understanding Android background restrictions."
        ))
    }

    @Test fun connectedGitHubRequestPrefersAuthenticatedAccountIntent() {
        val request = "inspect your connected GitHub account and identify your PhoneAI repository"
        assertTrue(OrionCommandIntentPolicy.isConnectedGitHubAccountRequest(request))
        assertEquals("PhoneAI", OrionCommandIntentPolicy.requestedGitHubRepositoryName(request))
    }

    @Test fun connectedGitHubIntentRecognizesLinkedAccountLanguage() {
        assertTrue(OrionCommandIntentPolicy.isConnectedGitHubAccountRequest(
            "Check my linked GitHub account and list its repositories"
        ))
    }

    @Test fun unrelatedPublicRepositoryLookupDoesNotBecomeAnAccountLookup() {
        assertFalse(OrionCommandIntentPolicy.isConnectedGitHubAccountRequest(
            "Find the public repository PhoneAI on GitHub"
        ))
    }

    @Test fun explicitPythonCreationAndExecutionIsAnActionRequest() {
        assertTrue(OrionCommandIntentPolicy.requiresPythonExecution(
            "write a Python code calculator, and execute it"
        ))
        assertTrue(OrionCommandIntentPolicy.isCalculatorExecutionRequest(
            "write a Python code calculator, and execute it"
        ))
        assertTrue(OrionCommandIntentPolicy.requiresPythonExecution(
            "I have a Python script; please run it"
        ))
    }

    @Test fun codeOnlyAndNegatedExecutionRequestsStayNonExecuting() {
        assertFalse(OrionCommandIntentPolicy.requiresPythonExecution(
            "Write a Python calculator but do not execute it"
        ))
        assertFalse(OrionCommandIntentPolicy.requiresPythonExecution(
            "Explain how Python calculators work"
        ))
    }

    @Test fun compoundResearchMemoryAndReminderRequestUsesStructuredPlanning() {
        val request = "Create a task to research the latest Android AI developments, summarize the findings, save the important lessons to memory and remind me tomorrow."
        assertTrue(OrionCommandIntentPolicy.requiresStructuredTaskPlanning(request))
        assertTrue(OrionCommandIntentPolicy.requiresDirectActionRouting(request))
    }

    @Test fun simpleResearchRoutesDirectlyWithoutBecomingAMultiStepTask() {
        val request = "Research the latest Android on-device AI developments."
        assertTrue(OrionCommandIntentPolicy.requiresDirectActionRouting(request))
        assertFalse(OrionCommandIntentPolicy.requiresStructuredTaskPlanning(request))
    }

    @Test fun calculatorWriteSaveAndExecuteUsesOneDeterministicVerifiedWorkflow() {
        val request = "Write a Python calculator, save it as a file and execute it."
        assertTrue(OrionCommandIntentPolicy.requiresPythonExecution(request))
        assertTrue(OrionCommandIntentPolicy.isCalculatorExecutionRequest(request))
        assertFalse("Calculator save-and-run is a single deterministic workflow, not a planner-only compound task",
            OrionCommandIntentPolicy.requiresStructuredTaskPlanning(request))
        assertTrue(OrionCommandIntentPolicy.requiresDirectActionRouting(request))
    }

    @Test fun investigationRequestsSkipTheChatOnlyActionClassifier() {
        assertTrue(OrionCommandIntentPolicy.requiresDirectActionRouting(
            "I want you to investigate my PhoneAI project, understand its condition and identify its weaknesses."
        ))
    }
}
