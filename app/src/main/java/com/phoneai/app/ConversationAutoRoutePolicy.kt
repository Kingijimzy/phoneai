package com.phoneai.app

/**
 * Deterministic intent routing for clear requests that should never be answered with
 * "I cannot access a terminal" merely because the user is in Talk mode.
 *
 * This only chooses the guarded agent path. It does not authorize tools, grant Android
 * permissions, or bypass the normal dispatch/approval policy.
 */
internal object ConversationAutoRoutePolicy {
    private val runtimeInspection = Regex(
        "(?i)\\b(check|inspect|verify|tell me|show me|find out|see)\\b.*\\b(python3?|pip3?|linux terminal|linux runtime|pr oot|proot|termux|shell|terminal)\\b.*\\b(installed|install|version|available|working|present)\\b|" +
            "\\b(python3?|pip3?)\\s+--version\\b|" +
            "\\b(which|whereis)\\s+(python3?|pip3?)\\b"
    )

    private val publicRepositoryInspection = Regex(
        "(?i)\\b(check|inspect|explain|describe|summari[sz]e|tell me about|what is|what's|read)\\b.*" +
            "\\b(github|git hub)\\b.*\\b(repo|repository|project)\\s+(?:called\\s+|named\\s+|is\\s+)?[a-z0-9_.-]{2,100}\\b|" +
            "\\b(github|git hub)\\s+(?:repo|repository|project)\\s+[a-z0-9_.-]{2,100}\\b"
    )

    private val explicitWebsiteVisit = Regex(
        "(?i)\\b(go to|visit|open|browse to|navigate to)\\s+(?:the\\s+)?[a-z0-9][a-z0-9.-]{1,100}\\s+(?:site|website|page)\\b|" +
            "\\b(go to|visit|open|browse to|navigate to)\\s+https?://"
    )

    fun requiresToolAgent(message: String): Boolean {
        val text = message.trim()
        if (text.isEmpty()) return false
        return runtimeInspection.containsMatchIn(text) ||
            publicRepositoryInspection.containsMatchIn(text) ||
            explicitWebsiteVisit.containsMatchIn(text)
    }
}
