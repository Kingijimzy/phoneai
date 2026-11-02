package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionDelegatedContextPolicyTest {
    @Test fun tradingWorkerGetsOnlyTradingStrategyAndJournalContext() {
        val scope = OrionDelegatedContextPolicy.forAccess(OrionAgentDelegation.Access.TRADING_ANALYSIS)
        assertTrue(scope.includeTradingStrategyAndJournal)
        assertTrue(scope.memoryCategories.isEmpty())
        assertFalse(scope.includeAgentLessons)
        assertFalse(scope.includeTaskSummaries)
        assertFalse(scope.includeKnowledgePassages)
    }

    @Test fun researchWorkerGetsFactsAndKnowledgeButNoTasksOrLessons() {
        val scope = OrionDelegatedContextPolicy.forAccess(OrionAgentDelegation.Access.READ_ONLY_RESEARCH)
        assertEquals(setOf("fact"), scope.memoryCategories)
        assertTrue(scope.includeKnowledgePassages)
        assertFalse(scope.includeAgentLessons)
        assertFalse(scope.includeTaskSummaries)
        assertFalse(scope.includeTradingStrategyAndJournal)
    }

    @Test fun plannerGetsOnlyActiveTaskSummaries() {
        val scope = OrionDelegatedContextPolicy.forAccess(OrionAgentDelegation.Access.TASK_PLANNING)
        assertTrue(scope.includeTaskSummaries)
        assertTrue(scope.memoryCategories.isEmpty())
        assertFalse(scope.includeAgentLessons)
        assertFalse(scope.includeKnowledgePassages)
        assertFalse(scope.includeTradingStrategyAndJournal)
    }

    @Test fun analysisWorkerGetsItsLessonsAndRelevantFactsOnly() {
        val scope = OrionDelegatedContextPolicy.forAccess(OrionAgentDelegation.Access.ANALYSIS_ONLY)
        assertEquals(setOf("fact"), scope.memoryCategories)
        assertTrue(scope.includeAgentLessons)
        assertFalse(scope.includeTaskSummaries)
        assertFalse(scope.includeKnowledgePassages)
        assertFalse(scope.includeTradingStrategyAndJournal)
    }
}
