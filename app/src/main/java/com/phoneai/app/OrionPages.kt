package com.phoneai.app

import android.content.Context
import android.content.Intent

/**
 * Every screen in the platform, in one registry. The chat is home; pages are rooms Orion can open for the owner
 * (open_page tool) and the owner can reach from the command palette. Nothing here is a separate app.
 */
internal object OrionPages {
    data class Page(val id: String, val title: String, val about: String, val cls: Class<*>, val keywords: String = "", val startPage: String? = null)

    val all: List<Page> = listOf(
        Page("chat", "Chat (home)", "The conversation with Orion", MainChatActivity::class.java, "home talk"),
        Page("live", "Live call", "Hands-free voice conversation", LiveConversationActivity::class.java, "voice call talk"),
        Page("studio", "Code Studio", "Editor, files, terminal, Git, build, runtime", StudioActivity::class.java, "code ide lsp git debug build registry plugins mcp marketplace"),
        Page("terminal", "Terminal", "Linux shell and sandbox", StudioActivity::class.java, "shell linux bash proot", "terminal"),
        Page("workspace", "Workspace", "Files and projects Orion builds", StudioActivity::class.java, "files code project", "editor"),
        Page("browser", "Browser", "Orion's built-in browser", BrowserActivity::class.java, "web internet"),
        Page("tasks", "Tasks", "Objectives, missions and goals", TaskCenterActivity::class.java, "todo missions goals"),
        Page("agents", "Specialists", "Sub-agents and specialists", AgentsActivity::class.java, "agent subagent coder"),
        Page("capabilities", "Capabilities", "Every tool Orion has", CapabilityBoardActivity::class.java, "tools"),
        Page("extensions", "Extension lab", "Tools Orion built for itself", SelfExtensionLabActivity::class.java, "extension self"),
        Page("memory", "Memory", "What Orion remembers", MemoryActivity::class.java, "remember"),
        Page("knowledge", "Knowledge graph", "People, projects, facts", KnowledgeGraphActivity::class.java, "graph"),
        Page("soul", "Soul engine", "Orion's drives and state", SoulEngineActivity::class.java, "mind drives"),
        Page("proactive", "Proactive", "What Orion does on its own", ProactiveActivity::class.java, "autonomy initiative"),
        Page("models", "Models", "Providers and models", ModelSettingsActivity::class.java, "llm gemini deepseek"),
        Page("voice", "Voice settings", "Voice, speed, barge-in", VoiceSettingsActivity::class.java, "tts kokoro"),
        Page("telegram", "Telegram", "Remote line to the owner", TelegramSettingsActivity::class.java, "bot report"),
        Page("mcp", "MCP servers", "External tool servers", McpSettingsActivity::class.java, "mcp"),
        Page("accounts", "Accounts", "Connected services and social", AccountConnectionsActivity::class.java, "facebook youtube social"),
        Page("trading", "Trading desk", "Trading research and approvals", TradingDeskActivity::class.java, "bybit trade"),
        Page("gaming", "Gaming coach", "Game assistance", GamingCoachActivity::class.java, "game"),
        Page("teach", "Teach mode", "Show Orion a routine", TeachModeActivity::class.java, "macro routine"),
        Page("settings", "Settings", "All settings", SettingsActivity::class.java, "preferences"),
        Page("control", "Control center", "Owner control center", OwnerControlCenterActivity::class.java, "owner"),
        Page("report", "Capability report", "What works and what does not", OrionCapabilityReportActivity::class.java, "health audit")
    )

    fun find(name: String?): Page? {
        val n = name.orEmpty().trim().lowercase()
        if (n.isEmpty()) return null
        return all.firstOrNull { it.id == n || it.title.lowercase() == n }
            ?: all.firstOrNull { n in it.title.lowercase() || n in it.keywords || it.id in n }
    }

    fun search(query: String): List<Page> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all
        return all.filter { q in it.title.lowercase() || q in it.about.lowercase() || q in it.keywords || q in it.id }
    }

    fun list(): String = all.joinToString("\n") { "- ${it.id}: ${it.title} — ${it.about}" }

    fun open(c: Context, name: String?): String {
        val page = find(name) ?: return "error: unknown page '${name.orEmpty()}'. Pages:\n" + list()
        return try {
            c.startActivity(Intent(c, page.cls).also { i -> page.startPage?.let { i.putExtra("page", it) } }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened ${page.title}."
        } catch (e: Exception) {
            "error: could not open ${page.title} (${e.javaClass.simpleName})"
        }
    }
}
