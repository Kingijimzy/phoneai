package com.phoneai.app

/** Rejects planner filler words before spending a network request on a meaningless repository search. */
internal object GitHubSearchQueryPolicy {
    private val filler = setOf("and", "or", "the", "a", "an", "my", "your", "project", "repo", "repository", "please")

    fun rejection(query: String): String? {
        val value = query.trim()
        if (value.isBlank()) return "error: provide a meaningful public repository name or search phrase"
        if (value.lowercase() in filler) {
            return "error: '$value' is a filler word, not a repository search. Use a named owner/repository with github_public, or provide meaningful search terms."
        }
        if (!Regex("^[A-Za-z0-9_. -]{1,100}$").matches(value)) {
            return "error: provide a simple public repository name"
        }
        return null
    }
}
