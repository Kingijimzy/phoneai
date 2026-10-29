package com.phoneai.app

/**
 * Resolves short research follow-ups against the most recent user request.
 * This is deliberately narrow: it only rewrites explicit research continuations and never
 * turns an unrelated message into a web lookup.
 */
internal object ResearchFollowUpPolicy {
    private val followUp = Regex(
        "(?i)^\\s*(?:(?:please|can you|could you|now)\\s+)*(?:go and )?(?:do|conduct|continue|finish|carry out)\\s+(?:your )?research(?: about| on| into)?\\s+(?:it|that|this|them|those)?[.!?\\s]*$|" +
            "^\\s*(?:research|look it up|search for it|check it online|verify it|find out)\\s*(?:about|on|for)?\\s*(?:it|that|this)?[.!?\\s]*$"
    )

    fun isResearchFollowUp(message: String): Boolean = followUp.matches(message.trim())

    fun resolveTarget(message: String, previousUserRequest: String?): String? {
        if (!isResearchFollowUp(message)) return null
        val previous = previousUserRequest?.trim().orEmpty()
        if (previous.isBlank() || isResearchFollowUp(previous)) return null
        return previous.take(1200)
    }

    fun commandFor(message: String, previousUserRequest: String?): String {
        val target = resolveTarget(message, previousUserRequest) ?: return message.trim()
        return "Research this $target"
    }
}
