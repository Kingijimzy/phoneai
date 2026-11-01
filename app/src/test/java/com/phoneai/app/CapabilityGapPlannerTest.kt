package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class CapabilityGapPlannerTest {
    private fun tool(name: String, description: String) =
        ToolDefinition(name, "$name {}: $description", description, ToolAccess.READ)

    @Test fun reusesExistingToolInsteadOfSuggestingDuplicateExtension() {
        val result = CapabilityGapPlanner.assess(
            request = "summarize recent public news headlines",
            registeredTools = listOf(tool("live_news", "retrieve recent public news headlines and URLs")),
            enabledSkills = emptyList(),
            extensions = emptyList()
        )
        assertEquals(CapabilityGapRoute.REUSE_TOOL, result.route)
        assertTrue(result.candidates.contains("live_news"))
        assertTrue(result.nextActions.any { it.contains("existing") || it.contains("registered") })
    }

    @Test fun recommendsResearchAndRealTestsOnlyWhenNoLocalRouteMatches() {
        val result = CapabilityGapPlanner.assess(
            request = "convert irregular recipe ingredients into a normalized shopping list",
            registeredTools = listOf(tool("battery", "report device battery status")),
            enabledSkills = emptyList(),
            extensions = emptyList()
        )
        assertEquals(CapabilityGapRoute.INVESTIGATE_THEN_DRAFT, result.route)
        assertTrue(result.nextActions.any { it.contains("public documentation") || it.contains("open-source") })
        assertTrue(result.nextActions.any { it.contains("extension_test") && it.contains("expected-output") })
        assertTrue(result.nextActions.any { it.contains("explicit approval") })
    }

    @Test fun onlyVerifiedHashMatchingExtensionsAreCandidates() {
        val source = "RESULT = INPUT['value']"
        val ext = GeneratedExtension(
            id = "recipe_helper",
            displayName = "Recipe helper",
            runtime = "python",
            source = source,
            sourceSha256 = SelfExtendingExtensionPolicy.sha256("tampered"),
            description = "normalize recipe ingredient shopping list",
            createdAtMillis = 1L,
            enabled = true,
            verified = true
        )
        val result = CapabilityGapPlanner.assess(
            request = "normalize recipe ingredient shopping list",
            registeredTools = emptyList(),
            enabledSkills = emptyList(),
            extensions = listOf(ext)
        )
        assertEquals(CapabilityGapRoute.INVESTIGATE_THEN_DRAFT, result.route)
        assertFalse(result.candidates.contains("recipe_helper"))
    }

    @Test fun blankGoalRequestsClarificationInsteadOfInventingAGap() {
        val result = CapabilityGapPlanner.assess("", emptyList(), emptyList(), emptyList())
        assertEquals(CapabilityGapRoute.NEEDS_CLARIFICATION, result.route)
        assertTrue(result.nextActions.single().contains("intended outcome"))
    }
}
