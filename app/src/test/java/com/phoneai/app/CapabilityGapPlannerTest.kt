package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class CapabilityGapPlannerTest {
    private fun tool(name: String, description: String) =
        ToolDefinition(name, "$name {}: $description", description, ToolAccess.READ)

    @Test fun modelAssessesSemanticFitInsteadOfLexicalToolScoring() {
        val tools = listOf(tool("live_news", "retrieve recent public news headlines and URLs"))
        val news = CapabilityGapPlanner.assess(
            request = "summarize recent public news headlines",
            registeredTools = tools,
            enabledSkills = emptyList(),
            extensions = emptyList()
        )
        val unrelated = CapabilityGapPlanner.assess(
            request = "news headlines public recent summarize",
            registeredTools = tools,
            enabledSkills = emptyList(),
            extensions = emptyList()
        )
        assertEquals(CapabilityGapRoute.MODEL_DECIDES, news.route)
        assertEquals(CapabilityGapRoute.MODEL_DECIDES, unrelated.route)
        assertTrue(news.reason.contains("No lexical similarity score"))
        assertTrue(news.nextActions.any { it.contains("semantic fit") || it.contains("objective-level") })
        assertTrue(news.nextActions.any { it.contains("capability_search") && it.contains("capability_load") })
    }

    @Test fun capabilityGapContextRequiresReuseAndVerifiedExtensionLifecycle() {
        val result = CapabilityGapPlanner.assess(
            request = "convert irregular recipe ingredients into a normalized shopping list",
            registeredTools = listOf(tool("battery", "report device battery status")),
            enabledSkills = emptyList(),
            extensions = emptyList()
        )
        assertEquals(CapabilityGapRoute.MODEL_DECIDES, result.route)
        assertTrue(result.nextActions.any { it.contains("Reuse registered tools") })
        assertTrue(result.nextActions.any { it.contains("genuine capability gap") })
        assertTrue(result.nextActions.any { it.contains("Android framework") })
    }

    @Test fun onlyVerifiedHashMatchingExtensionsAppearInInventory() {
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
        assertEquals(CapabilityGapRoute.MODEL_DECIDES, result.route)
        assertFalse(result.candidates.any { it.contains("recipe_helper") })
    }

    @Test fun blankGoalRequestsClarificationInsteadOfInventingAGap() {
        val result = CapabilityGapPlanner.assess("", emptyList(), emptyList(), emptyList())
        assertEquals(CapabilityGapRoute.NEEDS_CLARIFICATION, result.route)
        assertTrue(result.nextActions.single().contains("intended outcome"))
    }

    @Test fun verifiedExtensionsAreExecutedAndReusedBeforeNewGeneration() {
        val source = "RESULT = INPUT['value']"
        val extension = GeneratedExtension(
            id = "value_helper", displayName = "Value helper", runtime = "python",
            source = source, sourceSha256 = SelfExtendingExtensionPolicy.sha256(source),
            description = "normalizes a value", createdAtMillis = 1L, enabled = true, verified = true
        )
        val result = CapabilityGapPlanner.assess(
            request = "normalize this value", registeredTools = emptyList(), enabledSkills = emptyList(), extensions = listOf(extension)
        )
        assertTrue(result.nextActions.any { it.contains("extension_list") && it.contains("extension_run") })
        assertTrue(result.nextActions.any { it.contains("exact source hash") && it.contains("real runtime") })
    }

    @Test fun nativeCapabilityGapsRouteThroughDeveloperBuildAndVerificationWorkflow() {
        val result = CapabilityGapPlanner.assess(
            request = "add a native Android accessibility capability", registeredTools = emptyList(), enabledSkills = emptyList(), extensions = emptyList()
        )
        val route = result.nextActions.joinToString(" ")
        listOf("dev_understand", "dev_read", "dev_plan", "dev_write", "dev_shell", "Gradle", "unit tests", "artifacts", "GitHub").forEach {
            assertTrue("Native developer route must include $it", route.contains(it, ignoreCase = true))
        }
        assertTrue(route.contains("Python extensions can add reusable data/workflow logic"))
        assertTrue(route.contains("cannot add Android framework"))
    }
}
