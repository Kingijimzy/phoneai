package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionDelegatedContextPolicyTest {
    /**
     * The trading specialist must be able to read evidence-traced educator video notes,
     * which live in the "project" memory category, and relevant user-document passages.
     * Private personal categories stay closed: this is a widening of educational scope only.
     */
    @Test fun tradingWorkerGetsStrategyJournalAndEducationalProjectNotes() {
        val scope = OrionDelegatedContextPolicy.forAccess(OrionAgentDelegation.Access.TRADING_ANALYSIS)
        assertTrue(scope.includeTradingStrategyAndJournal)
        assertEquals(setOf("project"), scope.memoryCategories)
        assertTrue(scope.includeKnowledgePassages)
        assertFalse(scope.includeAgentLessons)
        assertFalse(scope.includeTaskSummaries)
    }

    /** Guard the privacy boundary: personal categories must never leak into trading analysis. */
    @Test fun tradingWorkerNeverGetsPersonalMemoryCategories() {
        val scope = OrionDelegatedContextPolicy.forAccess(OrionAgentDelegation.Access.TRADING_ANALYSIS)
        listOf("fact", "preference", "decision", "conversation", "task").forEach {
            assertFalse("$it must stay out of trading delegation", it in scope.memoryCategories)
        }
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
