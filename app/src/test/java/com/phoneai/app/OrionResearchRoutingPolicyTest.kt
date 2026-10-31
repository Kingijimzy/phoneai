package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionResearchRoutingPolicyTest {
    @Test fun routesGitHubAccountAndRepositoryRequestsToTools() {
        assertTrue(OrionResearchRoutingPolicy.requiresToolAgent("check my GitHub account"))
        assertTrue(OrionResearchRoutingPolicy.requiresToolAgent("check this repo and tell me what you see https://github.com/Kingijimzy/phoneai"))
        assertTrue(OrionResearchRoutingPolicy.requiresToolAgent("look at the latest GitHub Actions run"))
        assertTrue(OrionResearchRoutingPolicy.requiresToolAgent("show my GitHub notifications"))
    }

    @Test fun routesApkVisionAndAppFileSizeResearchToTools() {
        assertTrue(OrionResearchRoutingPolicy.requiresToolAgent("check APKVision for PES Pro Soccer APK size"))
        assertTrue(OrionResearchRoutingPolicy.requiresToolAgent("what size is the APK and extra files for this game"))
        assertTrue(OrionResearchRoutingPolicy.requiresToolAgent("find the latest APK download size on APKMirror"))
    }

    @Test fun doesNotRouteOrdinaryConversationOrUnrelatedQuestions() {
        assertFalse(OrionResearchRoutingPolicy.requiresToolAgent("I like GitHub"))
        assertFalse(OrionResearchRoutingPolicy.requiresToolAgent("Explain what an APK is"))
        assertFalse(OrionResearchRoutingPolicy.requiresToolAgent("What is the weather like?"))
        assertFalse(OrionResearchRoutingPolicy.requiresToolAgent(""))
    }
}
