package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionRecoveryPlannerTest {
    @Test fun unverifiedActionInspectsStateBeforeAnyRetry() {
        val guidance = OrionRecoveryPlanner.forFailure("click", "error: verification failed: no visible screen change")
        assertEquals(OrionRecoveryPlanner.Strategy.INSPECT_STATE, guidance.strategy)
        assertTrue(guidance.mustNotRepeatSameCall)
    }

    @Test fun deniedApprovalStopsInsteadOfReprompting() {
        val guidance = OrionRecoveryPlanner.forFailure("sms", "error: user did not approve this tool action")
        assertEquals(OrionRecoveryPlanner.Strategy.STOP_AND_REPORT, guidance.strategy)
    }

    @Test fun missingPermissionRequestsSetupAndRediscovery() {
        val guidance = OrionRecoveryPlanner.forFailure("read_notifications", "error: notification permission is missing")
        assertEquals(OrionRecoveryPlanner.Strategy.REQUEST_PERMISSION_OR_SETUP, guidance.strategy)
        assertTrue(guidance.promptLine().contains("rediscover capability state"))
    }

    @Test fun safetyBlockNeverSuggestsBypass() {
        val guidance = OrionRecoveryPlanner.forFailure("shell", "error: blocked by safety policy")
        assertEquals(OrionRecoveryPlanner.Strategy.STOP_AND_REPORT, guidance.strategy)
        assertTrue(guidance.reason.contains("bypass"))
    }

    @Test fun transientFailureAvoidsRapidRetry() {
        val guidance = OrionRecoveryPlanner.forFailure("browser_navigate", "error: request timeout")
        assertEquals(OrionRecoveryPlanner.Strategy.CHOOSE_DIFFERENT_METHOD, guidance.strategy)
        assertTrue(guidance.reason.contains("different registered source/provider"))
    }

    @Test fun staleTargetUsesFreshEvidenceAndDifferentArguments() {
        val guidance = OrionRecoveryPlanner.forFailure("click", "error: target not found")
        assertEquals(OrionRecoveryPlanner.Strategy.CHOOSE_DIFFERENT_METHOD, guidance.strategy)
        assertTrue(guidance.reason.contains("stale arguments"))
    }
    @Test fun alternativeSelectionUsesOnlyPermittedRegisteredDifferentTools() {
        val selected = OrionRecoveryPlanner.selectAlternatives(
            failedTool = "click",
            result = "error: target not found",
            registeredAndPermitted = setOf("click", "read_screen", "wait_for", "shell"),
            alreadyAttempted = setOf("shell")
        )
        assertEquals(listOf("read_screen", "wait_for"), selected)
        assertTrue(selected.none { it == "click" || it == "shell" })
    }

    @Test fun deniedOrCancelledActionsNeverGetAlternativeShortlist() {
        listOf("error: user did not approve", "error: blocked by safety policy", "error: task was stopped")
            .forEach { result ->
                assertTrue(OrionRecoveryPlanner.selectAlternatives(
                    "click", result, setOf("read_screen", "click")
                ).isEmpty())
            }
    }

    @Test fun uncertainOutcomePrefersReadOnlyInspectionAndRespectsLimit() {
        val selected = OrionRecoveryPlanner.selectAlternatives(
            failedTool = "browser_click",
            result = "error: verification unavailable; outcome unverified",
            registeredAndPermitted = setOf("read_screen", "verify_action", "wait_for", "click_text"),
            limit = 2
        )
        assertEquals(listOf("read_screen", "verify_action"), selected)
    }

    @Test fun researchProviderFailureChoosesDifferentRegisteredResearchSources() {
        val selected = OrionRecoveryPlanner.selectAlternatives(
            failedTool = "live_web_search",
            result = "error: provider request timed out",
            registeredAndPermitted = setOf("live_web_search", "research_web", "fetch_url", "read_screen"),
            alreadyAttempted = setOf("read_screen")
        )
        assertEquals(listOf("research_web", "fetch_url"), selected)
    }

}
