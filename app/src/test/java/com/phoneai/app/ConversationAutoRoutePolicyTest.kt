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

    @Test fun routesPublicRepositoryInspectionWithoutPrivateAccountAccess() {
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent(
            "check my GitHub repo phoneai and tell me what it is about"
        ))
        assertTrue(ConversationAutoRoutePolicy.requiresToolAgent(
            "tell me about GitHub repository nanoMuse"
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

    @Test fun ordinaryConversationDoesNotRouteToTools() {
        assertFalse(ConversationAutoRoutePolicy.requiresToolAgent("What is Python used for?"))
        assertFalse(ConversationAutoRoutePolicy.requiresToolAgent("Tell me about GitHub as a company"))
        assertFalse(ConversationAutoRoutePolicy.requiresToolAgent("Hey, how are you?"))
    }
}
