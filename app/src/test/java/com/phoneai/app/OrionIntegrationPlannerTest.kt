package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionIntegrationPlannerTest {
    @Test
    fun recognizesCommonIntegrationRequests() {
        assertEquals(OrionIntegrationPlanner.Provider.GITHUB,
            OrionIntegrationPlanner.providerFor("connect my GitHub account"))
        assertEquals(OrionIntegrationPlanner.Provider.GMAIL,
            OrionIntegrationPlanner.providerFor("read my Gmail messages"))
        assertEquals(OrionIntegrationPlanner.Provider.TERMUX,
            OrionIntegrationPlanner.providerFor("install the missing tool in Termux"))
    }

    @Test
    fun connectedGmailStillFlagsPrivateDataAndRequiresAuthorizationContext() {
        val plan = OrionIntegrationPlanner.plan(
            OrionIntegrationPlanner.Provider.GMAIL,
            OrionIntegrationPlanner.State.CONNECTED,
            "read my inbox messages",
            setOf("gmail.messages.read")
        )
        assertEquals(OrionIntegrationPlanner.NextStep.USE_EXISTING, plan.nextStep)
        assertTrue(plan.needsUserAuthorization)
        assertTrue(plan.mayReadPrivateData)
    }

    @Test
    fun missingGitHubCapabilityRequiresResearchAndProposal() {
        val plan = OrionIntegrationPlanner.plan(
            OrionIntegrationPlanner.Provider.GITHUB,
            OrionIntegrationPlanner.State.UNKNOWN,
            "connect GitHub and inspect my repository",
            emptySet()
        )
        assertEquals(OrionIntegrationPlanner.NextStep.RESEARCH_AND_PROPOSE, plan.nextStep)
        assertTrue(plan.needsUserAuthorization)
        assertTrue(plan.requiredCapabilities.contains("github.account.read"))
    }

    @Test
    fun availableButUnconnectedServiceMustAskBeforeConnecting() {
        val plan = OrionIntegrationPlanner.plan(
            OrionIntegrationPlanner.Provider.GMAIL,
            OrionIntegrationPlanner.State.AVAILABLE_NOT_CONNECTED,
            "connect Gmail",
            setOf("gmail.messages.read")
        )
        assertEquals(OrionIntegrationPlanner.NextStep.ASK_TO_CONNECT, plan.nextStep)
    }

    @Test
    fun connectedAccountWithoutRegisteredConnectorIsReportedAsCapabilityMissing() {
        assertEquals(
            OrionIntegrationPlanner.State.CAPABILITY_MISSING,
            OrionIntegrationPlanner.capabilityAwareState(
                OrionIntegrationPlanner.Provider.GMAIL,
                OrionIntegrationPlanner.State.CONNECTED,
                emptySet()
            )
        )
        assertEquals(
            OrionIntegrationPlanner.State.CONNECTED,
            OrionIntegrationPlanner.capabilityAwareState(
                OrionIntegrationPlanner.Provider.GMAIL,
                OrionIntegrationPlanner.State.CONNECTED,
                setOf("gmail.messages.read")
            )
        )
    }

    @Test
    fun unconnectedProviderIsNotRelabelledAsConnectedOrCapabilityMissing() {
        assertEquals(
            OrionIntegrationPlanner.State.AVAILABLE_NOT_CONNECTED,
            OrionIntegrationPlanner.capabilityAwareState(
                OrionIntegrationPlanner.Provider.GITHUB,
                OrionIntegrationPlanner.State.AVAILABLE_NOT_CONNECTED,
                emptySet()
            )
        )
    }

    @Test
    fun unknownServiceDoesNotAssumeAccess() {
        val plan = OrionIntegrationPlanner.plan(
            OrionIntegrationPlanner.Provider.UNKNOWN,
            OrionIntegrationPlanner.State.UNKNOWN,
            "connect my account",
            emptySet()
        )
        assertEquals(OrionIntegrationPlanner.NextStep.CLARIFY, plan.nextStep)
        assertFalse(plan.requiredCapabilities.isNotEmpty())
    }
}
