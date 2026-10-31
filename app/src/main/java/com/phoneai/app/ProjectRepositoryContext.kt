package com.phoneai.app

/** Stable owner/repository identity for named first-party projects the agent is expected to inspect. */
internal object ProjectRepositoryContext {
    private val phoneAiMention = Regex(
        """(?i)\b(?:my\s+)?phoneai\s+(?:project|repo|repository)\b"""
    )

    fun forRequest(request: String?): String? {
        if (request.isNullOrBlank() || !phoneAiMention.containsMatchIn(request)) return null
        return "NAMED PROJECT REPOSITORY: PhoneAI is the public GitHub repository Kingijimzy/phoneai (https://github.com/Kingijimzy/phoneai). " +
            "For source inspection, use github_public with owner=Kingijimzy and repo=phoneai, first section=tree, then read exact relevant files with section=file and a repository-relative path. " +
            "Do not run a repository search for filler words, conjunctions, or the word 'project'; never invent a path or claim code was inspected unless the file content was returned."
    }
}
