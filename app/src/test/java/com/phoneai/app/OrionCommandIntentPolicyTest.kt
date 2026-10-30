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
}
