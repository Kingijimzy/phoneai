package com.phoneai.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/** The capability ring on the neural home: every node is a real route Orion can use, lit by real tool calls. */
internal object OrionNodes {
    data class Node(val id: String, val label: String, val glyph: String, val alwaysLinked: Boolean = false)

    val all: List<Node> = listOf(
        Node("gmail", "Gmail", "M"), Node("calendar", "Calendar", "31", true), Node("drive", "Drive", "▲"),
        Node("docs", "Docs", "D"), Node("sheets", "Sheets", "▦"), Node("notion", "Notion", "N"),
        Node("memory", "Memory", "◉", true), Node("chat", "Chat", "✦", true), Node("voice", "Voice", "◖", true),
        Node("web", "Web", "◎", true), Node("files", "Files", "▤", true), Node("shell", "Shell", ">_", true),
        Node("reddit", "Reddit", "r/"), Node("facebook", "Facebook", "f"), Node("twitter", "Twitter", "𝕏"),
        Node("linkedin", "LinkedIn", "in"), Node("youtube", "YouTube", "▶")
    )

    private val social = mapOf(
        "reddit" to "reddit", "facebook" to "facebook", "instagram" to "facebook", "threads" to "facebook",
        "x" to "twitter", "twitter" to "twitter", "linkedin" to "linkedin", "youtube" to "youtube"
    )

    /** Which node a tool call belongs to; null when it is internal bookkeeping. */
    fun nodeFor(tool: String, args: JSONObject? = null): String? {
        val t = tool.lowercase()
        return when {
            t == "social" -> social[(args?.optString("platform").orEmpty().ifBlank { args?.optString("id").orEmpty() }).lowercase()] ?: "web"
            t.startsWith("gmail") -> "gmail"
            t == "calendar_events" || t == "event_create" || t == "alarm" || t == "timer" || t == "time" -> "calendar"
            t.startsWith("drive") -> "drive"
            t.startsWith("doc") && t != "document_extract" -> "docs"
            t.startsWith("sheet") -> "sheets"
            t.startsWith("notion") -> "notion"
            t in setOf("remember", "recall", "update_memory", "forget", "fact_add", "fact_query", "fact_forget", "knowledge_search", "discussion_search", "decision_history") -> "memory"
            t.startsWith("voice") -> "voice"
            t in setOf("telegram_send", "telegram_inbox", "sms", "dial", "ask_user") || t.startsWith("telegram") -> "chat"
            t.startsWith("browser") || t in setOf("web_search", "live_web_search", "fetch_url", "research_web", "open_url", "live_news", "live_sports", "weather", "web_api", "github_public", "github_search_repositories") -> "web"
            t.startsWith("workspace") || t in setOf("find_files", "open_file", "save_file", "show_file", "document_extract", "backup_list", "backup_restore") -> "files"
            t in setOf("shell", "python_exec", "execute_system_op", "termux_command", "dev_shell", "dev_python") -> "shell"
            else -> null
        }
    }

    data class Pulse(val count: Int = 0, val lastAt: Long = 0L, val failed: Boolean = false)

    var pulses by mutableStateOf<Map<String, Pulse>>(emptyMap())
        private set

    fun record(tool: String, args: JSONObject?, failed: Boolean, now: Long = System.currentTimeMillis()) =
        recordNode(nodeFor(tool, args), failed, now)

    fun recordNode(id: String?, failed: Boolean, now: Long = System.currentTimeMillis()) {
        if (id == null) return
        val p = pulses[id] ?: Pulse()
        pulses = pulses + (id to Pulse(p.count + 1, now, failed))
    }

    fun reset() { pulses = emptyMap() }

    /** The page a node opens when tapped. */
    fun pageFor(id: String): String = when (id) {
        "memory" -> "memory"; "chat" -> "telegram"; "voice" -> "voice"; "web" -> "browser"
        "files" -> "workspace"; "shell" -> "terminal"; "calendar" -> "tasks"; else -> "accounts"
    }

    /** A node is lit while its last call is recent. */
    fun isHot(p: Pulse?, now: Long, windowMs: Long = 6_000L): Boolean = p != null && p.lastAt > 0 && now - p.lastAt < windowMs

    /** Which nodes are linked (connected) given saved social tokens and Gmail/Notion state. */
    fun linked(socialIds: Collection<String>, extras: Set<String> = emptySet()): Set<String> {
        val fromSocial = socialIds.mapNotNull { social[it.lowercase()] }
        return all.filter { it.alwaysLinked }.map { it.id }.toSet() + fromSocial + extras
    }

    fun headline(linked: Set<String>): String = "${all.count { it.id in linked }} of ${all.size} links active"

    /** Position on a ring, angle 0 at the top, clockwise. */
    fun ringPoint(index: Int, total: Int, cx: Float, cy: Float, rx: Float, ry: Float): Pair<Float, Float> {
        val a = (-Math.PI / 2) + 2 * Math.PI * index / total
        return (cx + rx * Math.cos(a)).toFloat() to (cy + ry * Math.sin(a)).toFloat()
    }
}
