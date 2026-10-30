package com.phoneai.app

/**
 * Deterministic intent routing for clear requests that should never be answered with
 * "I cannot access a terminal" merely because the user is in Talk mode.
 *
 * This only chooses the guarded agent path. It does not authorize tools, grant Android
 * permissions, or bypass the normal dispatch/approval policy.
 */
internal object ConversationAutoRoutePolicy {
    private val runtimeVerb = Regex("(?i)\\b(check|inspect|verify|tell me|show me|find out|see)\\b")
    private val runtimeTarget = Regex("(?i)\\b(python3?|pip3?|linux terminal|linux runtime|proot|termux|shell|terminal)\\b")
    private val runtimeState = Regex("(?i)\\b(installed|install|version|available|working|present)\\b")
    private val directRuntimeCommand = Regex("(?i)\\b(python3?|pip3?)\\s+--version\\b|\\b(which|whereis)\\s+(python3?|pip3?)\\b")

    // Explicit requests to create and execute code in a named terminal must reach the guarded tool agent,
    // even when the conversation is currently in Talk mode and even when the model could otherwise draft code.
    private val terminalCodeTask = Regex(
        "(?is)\\b(create|write|save|make|build)\\b.{0,100}\\b(code|script|program|file|calculator|python)\\b.{0,180}\\b(run|execute|test)\\b.{0,60}\\b(linux|alpine|termux|terminal|shell)\\b|" +
            "\\b(run|execute|test)\\b.{0,80}\\b(python|script|program|calculator|code)\\b.{0,60}\\b(linux|alpine|termux|terminal)\\b"
    )
    // Short follow-ups such as "test it yourself" resume the tool agent instead of returning a generic disclaimer.
    private val executeFollowUp = Regex("(?i)^\\s*(test|run|execute|try)\\s+(it|that|the code|the script)(\\s+yourself)?[.! ]*\\s*$")

    private val publicRepositoryInspection = Regex(
        "(?i)\\b(check|inspect|explain|describe|summari[sz]e|tell me about|what is|what's|read)\\b.*" +
            "\\b(github|git hub)\\b.*\\b(repo|repository|project)\\s+(?:called\\s+|named\\s+|is\\s+)?[a-z0-9_.-]{2,100}\\b|" +
            "\\b(github|git hub)\\s+(?:repo|repository|project)\\s+[a-z0-9_.-]{2,100}\\b"
    )

    private val repositoryNameBeforeType = Regex(
        "(?i)\\b[a-z0-9_.-]{2,100}\\s+(?:github\\s+)?(?:repo|repository|project)\\b"
    )

    private val explicitWebsiteVisit = Regex(
        "(?i)\\b(go to|visit|open|browse to|navigate to)\\s+(?:the\\s+)?[a-z0-9][a-z0-9.-]{1,100}\\s+(?:site|website|page)\\b|" +
            "\\b(go to|visit|open|browse to|navigate to)\\s+https?://"
    )

    fun requiresToolAgent(message: String): Boolean {
        val text = message.trim()
        if (text.isEmpty()) return false
        return (runtimeVerb.containsMatchIn(text) && runtimeTarget.containsMatchIn(text) && runtimeState.containsMatchIn(text)) ||
            directRuntimeCommand.containsMatchIn(text) ||
            terminalCodeTask.containsMatchIn(text) ||
            executeFollowUp.matches(text) ||
            publicRepositoryInspection.containsMatchIn(text) ||
            repositoryNameBeforeType.containsMatchIn(text) ||
            explicitWebsiteVisit.containsMatchIn(text)
    }
}
