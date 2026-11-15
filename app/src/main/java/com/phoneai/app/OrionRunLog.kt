package com.phoneai.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Backend-agnostic execution log. The HUD shows what Orion is doing in plain words; whether the work ran
 * in Python, the Linux shell, Shizuku or an API is an implementation detail kept in the step's output.
 */
internal object OrionRunLog {
    enum class Status { RUNNING, DONE, FAILED }

    data class Step(
        val id: Long, val tool: String, val title: String, val detail: String, val node: String?,
        val startedAt: Long, val endedAt: Long = 0L, val status: Status = Status.RUNNING, val output: String = ""
    )

    private val ids = AtomicLong(1)
    private const val KEEP = 40

    @Volatile var steps by mutableStateOf<List<Step>>(emptyList())
        private set

    fun begin(tool: String, args: JSONObject?, now: Long = System.currentTimeMillis()): Long {
        val id = ids.getAndIncrement()
        val s = Step(id, tool, title(tool, args), detail(args), OrionNodes.nodeFor(tool, args), now)
        steps = (steps + s).takeLast(KEEP)
        return id
    }

    fun finish(id: Long, output: String, failed: Boolean, now: Long = System.currentTimeMillis()) {
        steps = steps.map { if (it.id == id) it.copy(endedAt = now, status = if (failed) Status.FAILED else Status.DONE, output = output.take(20_000)) else it }
        steps.firstOrNull { it.id == id }?.let { OrionNodes.recordNode(it.node, failed, now) }
    }

    fun clear() { steps = emptyList() }

    fun durationMs(s: Step, now: Long): Long = ((if (s.endedAt == 0L) now else s.endedAt) - s.startedAt).coerceAtLeast(0L)

    fun durationLabel(ms: Long): String = if (ms < 1000) "${ms}ms" else if (ms < 10_000) "%.1fs".format(ms / 1000.0) else "${ms / 1000}s"

    fun runningCount(list: List<Step> = steps): Int = list.count { it.status == Status.RUNNING }

    /** Plain-language title: never names the runtime. */
    fun title(tool: String, args: JSONObject?): String {
        val t = tool.lowercase()
        return when {
            t == "execute_system_op" || t == "shell" || t == "dev_shell" || t == "termux_command" -> "Running a command"
            t == "python_exec" || t == "dev_python" -> "Running code"
            t == "social" -> "Working with " + (args?.optString("platform").orEmpty().ifBlank { "a social account" })
            t.startsWith("browser") || t == "open_url" -> "Using the browser"
            t in setOf("web_search", "live_web_search", "research_web", "live_news") -> "Searching the web"
            t == "fetch_url" || t == "web_api" -> "Reading from the web"
            t == "live_sports" -> "Checking live sports"
            t.startsWith("workspace") || t == "save_file" || t == "dev_write" -> "Saving to the workspace"
            t == "dev_read" || t == "open_file" || t == "find_files" -> "Reading files"
            t in setOf("remember", "update_memory", "fact_add") -> "Remembering"
            t in setOf("recall", "fact_query", "knowledge_search", "discussion_search") -> "Recalling"
            t.startsWith("gmail") -> "Checking mail"
            t.startsWith("telegram") -> "Messaging on Telegram"
            t == "open_page" -> "Opening a page"
            t.startsWith("voice") -> "Speaking"
            t == "wait" -> "Waiting"
            else -> tool.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    /** One short line about what the step is acting on (a query, path or command start). */
    fun detail(args: JSONObject?): String {
        if (args == null) return ""
        for (k in listOf("platform", "command", "cmd", "query", "url", "path", "page", "text", "prompt", "q", "name")) {
            val v = args.optString(k).trim()
            if (v.isNotEmpty()) return v.replace(Regex("\\s+"), " ").take(90)
        }
        return ""
    }
}
