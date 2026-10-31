package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionAgentDelegationTest {
    @Test fun researchSpecialistReceivesOnlyPublicReadCapabilities() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, "parent-task-42", "Research recent Android agent frameworks"
        )
        assertEquals(OrionAgentDelegation.Access.READ_ONLY_RESEARCH, manifest.access)
        assertEquals("parent-task-42", manifest.parentTaskId)
        assertTrue(manifest.allowedTools.containsAll(setOf("live_web_search", "live_news", "fetch_url")))
        assertFalse(manifest.allowedTools.any { it in setOf("telegram_send", "sms", "shell", "python_exec", "paper_trade") })
    }

    @Test fun customSpecialistDefaultsToAnalysisOnlyWithNoToolCapabilities() {
        val custom = AgentProfile("social_helper", "Social Helper", "Plans social content", "Create content plans and drafts.")
        val manifest = OrionAgentDelegation.create(custom, "parent-task-7", "Prepare a content calendar")
        assertEquals(OrionAgentDelegation.Access.ANALYSIS_ONLY, manifest.access)
        assertTrue(manifest.allowedTools.isEmpty())
    }

    @Test fun tradingCoachCannotPlaceOrdersFromItsDelegatedScope() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("coach")!!, "parent-trading-task", "Review the BTC chart and identify possible setups"
        )
        assertEquals(OrionAgentDelegation.Access.TRADING_ANALYSIS, manifest.access)
        assertEquals(setOf("bybit_public_ticker"), manifest.allowedTools)
        assertFalse(manifest.allowedTools.contains("paper_trade"))
        assertFalse(manifest.allowedTools.contains("trading_kill_switch"))
    }

    @Test fun delegatedPromptNamesParentObjectiveAndExplicitlyForbidsExternalActions() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, "parent-123", "Compare two research sources"
        )
        val prompt = manifest.promptBlock()
        assertTrue(prompt.contains("parent-123"))
        assertTrue(prompt.contains("Compare two research sources"))
        assertTrue(prompt.contains("Do not claim to execute phone actions"))
        assertTrue(prompt.contains("parent Agent must review"))
    }

    @Test fun delegationHasBoundedCallBudgetAndNoParentSecretInManifest() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, null, "Research this API key=super-secret and find public docs"
        )
        assertEquals(1, manifest.maxModelCalls)
        assertFalse(manifest.objective.contains("super-secret"))
    }
}
