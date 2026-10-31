package com.phoneai.app

/** Executable data-scope policy for specialist prompts; never derive permissions from prose labels. */
internal object OrionDelegatedContextPolicy {
    data class Scope(
        val memoryCategories: Set<String>,
        val includeAgentLessons: Boolean,
        val includeTaskSummaries: Boolean,
        val includeKnowledgePassages: Boolean,
        val includeTradingStrategyAndJournal: Boolean
    )

    fun forAccess(access: OrionAgentDelegation.Access): Scope = when (access) {
        OrionAgentDelegation.Access.TRADING_ANALYSIS -> Scope(
            memoryCategories = emptySet(),
            includeAgentLessons = false,
            includeTaskSummaries = false,
            includeKnowledgePassages = false,
            includeTradingStrategyAndJournal = true
        )
        OrionAgentDelegation.Access.READ_ONLY_RESEARCH -> Scope(
            memoryCategories = setOf("fact"),
            includeAgentLessons = false,
            includeTaskSummaries = false,
            includeKnowledgePassages = true,
            includeTradingStrategyAndJournal = false
        )
        OrionAgentDelegation.Access.TASK_PLANNING -> Scope(
            memoryCategories = emptySet(),
            includeAgentLessons = false,
            includeTaskSummaries = true,
            includeKnowledgePassages = false,
            includeTradingStrategyAndJournal = false
        )
        OrionAgentDelegation.Access.ANALYSIS_ONLY -> Scope(
            memoryCategories = setOf("fact"),
            includeAgentLessons = true,
            includeTaskSummaries = false,
            includeKnowledgePassages = false,
            includeTradingStrategyAndJournal = false
        )
    }
}
