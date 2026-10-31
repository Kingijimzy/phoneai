package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationAutoRoutePolicyTest {
    @Test fun routesPythonRuntimeChecksFromTalkMode() {
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent(
            "check whether python is installed in the Linux terminal and the version"
        ))
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent(
            "go and check whether python is installed in the Linux terminal and the version"
        ))
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent("python3 --version"))
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent("which python3"))
    }

    @Test fun routesTerminalCodeCreationAndExecutionFromTalkMode() {
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent(
            "write a calculator code in python and execute it Linux terminal"
        ))
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent(
            "create a Python script and run it in the Linux terminal"
        ))
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent("test it yourself"))
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent("run the script yourself"))
    }

    @Test fun routesPublicRepositoryInspectionWithoutPrivateAccountAccess() {
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent(
            "check my GitHub repo phoneai and tell me what it is about"
        ))
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent(
            "tell me about GitHub repository nanoMuse"
        ))
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent(
            "check my GitHub account and check phoneai repo"
        ))
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent(
            "check phoneai repository and explain what it does"
        ))
    }

    @Test fun routesExplicitWebsiteVisits() {
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent(
            "go to apkvision site and tell me the APK size"
        ))
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent(
            "open https://example.com and summarize it"
        ))
    }

    @Test fun explicitSubagentAssignmentsEnterTheParentOrchestrator() {
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent("Give the coder agent a task to inspect this bug"))
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent("Delegate the research task to the specialist"))
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent("Ask the planner agent to break this project into steps"))
        assertFalse(ConversationAutoRoutePolicy.requiresToolAgent("I have an agent at work"))
    }

    @Test fun ordinaryConversationDoesNotRouteToTools() {
        assertFalse(ConversationAutoRoutePolicy.requiresToolAgent("What is Python used for?"))
        assertFalse(ConversationAutoRoutePolicy.requiresToolAgent("Tell me about GitHub as a company"))
        assertFalse(ConversationAutoRoutePolicy.requiresToolAgent("Hey, how are you?"))
    }
}
