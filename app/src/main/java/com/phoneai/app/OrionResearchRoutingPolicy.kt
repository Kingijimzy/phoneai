package com.phoneai.app

/**
 * Deterministic routing for requests that require fresh public research or connected account data.
 * Keep this separate from model wording so "I don't have tools" cannot suppress a real tool route.
 */
internal object OrionResearchRoutingPolicy {
    private val explicitResearchVerb = Regex(
        "\\b(check|inspect|look at|look up|open|review|summarize|summarise|tell me about|find|search|research|browse|fetch|show|list)\\b",
        RegexOption.IGNORE_CASE
    )

    private val githubTarget = Regex(
        "\\b(github|git hub|github\\.com|repository|repositories|repo|repos|pull request|pull requests|commit|commits|workflow run|actions run|github account|github notifications)\\b",
        RegexOption.IGNORE_CASE
    )

    private val apkTarget = Regex(
        "\\b(apkvision|apkpure|apkmirror|play store|apk|xapk|apks|obb|app size|game size|download size|file size)\\b",
        RegexOption.IGNORE_CASE
    )

    private val sizeIntent = Regex(
        "\\b(size|how big|download|file size|apk size|install size|storage size|what size|how many mb|how many gb)\\b",
        RegexOption.IGNORE_CASE
    )

    fun requiresToolAgent(text: String): Boolean {
        val request = text.trim()
        if (request.isEmpty()) return false
        val githubResearch = githubTarget.containsMatchIn(request) &&
            (explicitResearchVerb.containsMatchIn(request) ||
                Regex("\\b(my github|my repositories|my repos|this repo|this repository)\\b", RegexOption.IGNORE_CASE).containsMatchIn(request) ||
                Regex("https?://(www\\.)?github\\.com/", RegexOption.IGNORE_CASE).containsMatchIn(request))
        val apkResearch = apkTarget.containsMatchIn(request) &&
            (sizeIntent.containsMatchIn(request) || explicitResearchVerb.containsMatchIn(request) ||
                Regex("\\b(what is|what's|tell me|find out|check)\\b", RegexOption.IGNORE_CASE).containsMatchIn(request))
        return githubResearch || apkResearch
    }
}
