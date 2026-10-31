package com.phoneai.app

/**
 * Deterministic task-level boundary for the agent tool loop.
 * The model's chosen tool is never sufficient authorization by itself.
 */
internal enum class TaskMode { CHAT, PHONE_AUTOMATION, RESEARCH, READ_ONLY }

internal object TaskScopePolicy {
    private val readOnlyIntent = Regex(
        "(?i)^\\s*(?:(?:please|can you|could you)\\s+)*(read[- ]only|inspect|inspect only|just read|only read|check only|without changing|do not change|don't change|no changes)\\b"
    )
    // Explicit URLs plus an inspection verb are actionable research, even when phrased naturally.
    private val explicitUrl = Regex("(?i)https?://[^\\s<>\\\"]+")
    private val urlInspectionIntent = Regex("(?i)\\b(check|inspect|open|visit|read|review|summari[sz]e|analy[sz]e|audit|scan|fetch|look at|look into|what is in|tell me about|explain)\\b")
        "(?i)^\\s*(?:(?:please|can you|could you|would you|will you|i want you to|i need you to|i want to|i need to|go do|go|do|use)\\s+)*(research|browse(?: the web| online| the internet)?|search(?: the web| online| the internet| the internet for| for| up)?|web_search|look up(?: online)?|find online|check online|check the web|check the internet|google|fetch (?:the )?(?:page|url|website)|open (?:this )?(?:url|website)|use online sources|from online sources|compare online|what is the weather|what's the weather|weather forecast|weather in|forecast for|latest news|news today)\\b"
    )
    // Account/integration work is an actionable task, not ordinary conversation. This only
    // classifies scope; the registered-tool, consent, permission, and provider-auth checks still apply.
    private val integrationIntent = Regex(
        "(?i)^\\s*(?:(?:please|can you|could you|would you|will you|i want you to|i need you to|i want to|i need to|i would like(?: to)?|help me(?: with this)?[:,]?)\\s+)*(?:connect|link|authorize|authenticate|sign in|log in|set up|setup|check|verify|inspect|show|open|start|begin|reconnect|disconnect|unlink)\\b.*\\b(github|git hub|gmail|google mail|google drive|drive files|email|inbox|account|services?|connected services|service status|integration|integrations|connector|connectors)\\b|^\\s*(?:is|are|was|were)\\b.*\\b(github|git hub|gmail|google mail|google drive|email|inbox|account|service|integration|connector)\\b.*\\b(connected|linked|authorized|available)\\b"
    )
    private val localSearchIntent = Regex(
        "(?i)^\\s*(?:(?:please|can you|could you|would you|will you)\\s+)*(find me|recommend|look for)\\b.*\\b(restaurant|hotel|store|shop|cafe|service|nearby|near me)\\b"
    )
    private val commandLead = "(?:(?:please|can you|could you|would you|will you|i want you to|i need you to|i want to|i need to|help me(?: with this)?[:,]?)\\s+)*"
    private val phoneIntent = Regex(
        "(?i)^\\s*" + commandLead + "(use (the )?(browser|app|phone|device)|open|launch|tap|click|press|scroll|swipe|type|send|call|text|message|reply|email|play|close|install|download|fill|login|log in|sign in|set alarm|set timer|change setting|turn on|turn off|read my|check my|look at my|screenshot|notification|undo|what time is it|check battery|battery level|device info|list apps|open camera)\\b"
    )
    private val contentActionIntent = Regex(
        "(?i)^\\s*" + commandLead + "(write|compose|draft|prepare)\\b.*\\b(message|email|reply|text|whatsapp|telegram)\\b"
    )
    private val memoryActionIntent = Regex(
        "(?i)^\\s*(please\\s+)?(can you\\s+)?(remember|recall|forget|save|store|update|pin|unpin|delete|clear)\\s+(that|this|my|me|i prefer|i like|i do not like|i don't like|to|for me|the note|the memory|memory|memories|note|notes)\\b"
    )
    private val taskActionIntent = Regex(
        "(?i)^\\s*(please\\s+)?(create|add|list|show|update|complete|finish|delete|remove|cancel|clear|schedule|mark)\\b.*\\b(tasks?|to-?dos?|reminders?)\\b|^\\s*(please\\s+)?(remind me|set (a )?reminder)\\b"
    )
    private val explicitAppAction = Regex(
        "(?i)^\\s*" + commandLead + "(open|launch|close|install|uninstall)\\s+(whatsapp|telegram|chrome|settings|camera|youtube)\\b|^\\s*" + commandLead + "(send|message|text|reply|call)\\b.*\\b(whatsapp|telegram|sms|message|text)\\b"
    )

    fun modeFor(request: String): TaskMode {
        val text = request.trim()
        if (text.isEmpty()) return TaskMode.CHAT
        // Informational questions about apps/services must not become device actions merely because
        // the app name appears. Explicit action verbs below still route to phone automation.
        val informationalAppQuestion = Regex("(?i)^(what|why|how|when|where|who|explain|describe|tell me about|compare|list the advantages of)\\b.*\\b(whatsapp|telegram|chrome|youtube|instagram|facebook)\\b.*$|^(what|why|how|when|where|who|explain|describe|tell me about|compare)\\b.*\\b(advantages|features|purpose|used for|difference)\\b.*$" )
        if (informationalAppQuestion.containsMatchIn(text) && !Regex("(?i)\\b(open|launch|close|send|message|text|reply|call|tap|click|type|install|uninstall)\\b").containsMatchIn(text)) return TaskMode.CHAT
        if (readOnlyIntent.containsMatchIn(text)) return TaskMode.READ_ONLY
        if (explicitUrl.containsMatchIn(text) && urlInspectionIntent.containsMatchIn(text)) return TaskMode.RESEARCH\n        // Explicit phone/device intent takes precedence over incidental mentions of websites.
        if (phoneIntent.containsMatchIn(text) || explicitAppAction.containsMatchIn(text) ||
            memoryActionIntent.containsMatchIn(text) || taskActionIntent.containsMatchIn(text) ||
            contentActionIntent.containsMatchIn(text) || integrationIntent.containsMatchIn(text)) {
            return TaskMode.PHONE_AUTOMATION
        }
        if (researchIntent.containsMatchIn(text) || localSearchIntent.containsMatchIn(text)) return TaskMode.RESEARCH
        return if (AgentHooks.isPhoneActionRequest(text)) TaskMode.PHONE_AUTOMATION else TaskMode.CHAT
    }

    /**
     * The scope a command runs under. A short follow-up ("yes", "go ahead", "continue", "the second one")
     * inherits the previous actionable request's scope for a few minutes, so confirmations are not
     * misread as plain chat and blocked.
     */
    fun scopeFor(command: String, previousScope: String, previousAgeMs: Long): String {
        val text = command.trim()
        val words = text.split(Regex("\\s+")).size
        if (modeFor(text) == TaskMode.CHAT && words <= 8 && previousScope.isNotBlank() &&
            previousAgeMs in 0..(10 * 60 * 1000L) && modeFor(previousScope) != TaskMode.CHAT) {
            return previousScope + "\n" + text
        }
        return text
    }

    fun blockReason(request: String, toolName: String): String? {
        val definition = ToolRegistry.find(toolName)
            ?: return "Tool '$toolName' is not registered."

        // Intent classification is a routing hint, not an authorization boundary. If a tool is
        // registered, the task-scope layer must not reject it merely because the request was
        // classified as CHAT or because another specialist category did not match a keyword.
        // Capability-pack settings, per-tool permissions, explicit DENY, Android permissions,
        // safety checks, cancellation, and fresh confirmations remain enforced at dispatch.
        // Task wording is not a second permission system. Whether a registered tool may
        // execute is decided by Orion Settings (pack + per-tool mode) and dispatch-time
        // safety/confirmation gates, not by a guessed intent label.
        return null
    }

    private fun categoryOf(name: String): String? = when {
        name.startsWith("browser_") || name in setOf("open_url", "web_search", "live_web_search", "live_news", "fetch_url", "research_web", "weather") -> "browser"
        name == "bybit_public_ticker" -> "trading"
        name.startsWith("telegram_") -> "telegram"
        name.startsWith("mcp_") || name == "integration_status" -> "mcp"
        name == "github_public" -> "github"
        name.startsWith("task_") || name == "skills_list" -> "tasks"
        name in setOf("read_notifications", "notification_action", "media_status", "media_control") -> "media"
        else -> null
    }
}
