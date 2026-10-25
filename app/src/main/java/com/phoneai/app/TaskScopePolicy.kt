package com.phoneai.app

/**
 * Deterministic task-level boundary for the agent tool loop.
 * The model's chosen tool is never sufficient authorization by itself.
 */
internal enum class TaskMode { CHAT, PHONE_AUTOMATION, RESEARCH, READ_ONLY }

internal object TaskScopePolicy {
    private val readOnlyIntent = Regex(
        "(?i)\\b(read[- ]only|inspect only|just read|only read|check only|without changing|do not change|don't change|no changes)\\b"
    )
    private val researchIntent = Regex(
        "(?i)\\b(research|browse the web|search the web|look up online|online sources|website|webpage|internet|news articles|compare online|weather|forecast)\\b"
    )
    private val phoneIntent = Regex(
        "(?i)\\b(open|launch|tap|click|press|scroll|swipe|type|send|call|text|message|reply|email|whatsapp|telegram|play|close|install|download|fill|login|log in|sign in|set alarm|set timer|change setting|turn on|turn off|read my|check my|look at my|screenshot|notification|remind|task|todo|remember|forget|update memory|undo|what time is it|check battery|battery level|device info|list apps|open camera)\\b"
    )

    fun modeFor(request: String): TaskMode {
        val text = request.trim()
        if (text.isEmpty()) return TaskMode.CHAT
        if (readOnlyIntent.containsMatchIn(text)) return TaskMode.READ_ONLY
        // Explicit phone/device intent takes precedence over incidental mentions of websites.
        if (phoneIntent.containsMatchIn(text)) return TaskMode.PHONE_AUTOMATION
        if (researchIntent.containsMatchIn(text)) return TaskMode.RESEARCH
        return if (AgentHooks.isPhoneActionRequest(text)) TaskMode.PHONE_AUTOMATION else TaskMode.CHAT
    }

    fun blockReason(request: String, toolName: String): String? {
        val definition = ToolRegistry.find(toolName)
            ?: return "Tool '$toolName' is not registered."
        val mode = modeFor(request)
        if (mode == TaskMode.CHAT) {
            return "This is a conversation task; phone and external tools are outside its scope."
        }
        if ((mode == TaskMode.READ_ONLY || mode == TaskMode.RESEARCH) &&
            definition.access != ToolAccess.READ) {
            return "$mode tasks may use read-only tools; '$toolName' can change state."
        }

        val category = categoryOf(toolName) ?: return null
        val matchingIntent = when (category) {
            "browser" -> Regex("(?i)\\b(web|website|browse|browser|google|search|url|link|http|site|online|internet|news|weather|research|chrome|download|source|article)\\b")
            "trading" -> Regex("(?i)\\b(bybit|trade|trading|crypto|bitcoin|btc|eth|coin|price|ticker|chart|market)\\b")
            "telegram" -> Regex("(?i)\\btelegram\\b")
            "mcp" -> Regex("(?i)\\b(mcp|connector|integration|server)\\b")
            "github" -> Regex("(?i)\\b(github|repo|repository|release|actions|issues)\\b")
            "tasks" -> Regex("(?i)\\b(task|tasks|todo|remind|reminder|deadline|skill|skills)\\b")
            "media" -> Regex("(?i)\\b(music|song|play|pause|skip|volume|notification|notifications|media|youtube|spotify)\\b")
            else -> return null
        }
        if (!matchingIntent.containsMatchIn(request)) {
            return "Task scope is limited to the user's stated purpose; '$toolName' belongs to the unrelated '$category' tool group."
        }
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
