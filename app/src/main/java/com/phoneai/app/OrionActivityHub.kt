package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The activity hub: pure logic that turns raw run steps into one summary per task, a lane (kind of work)
 * for each step, and the exact place a step can be opened (Code Studio page/file, URL, or an Orion page).
 */
internal object OrionActivityHub {
    enum class Lane(val label: String, val glyph: String) {
        CODE("CODING", "</>"), WEB("WEB", "◎"), MCP("MCP", "⌘"), RUNTIME("RUNTIME", "▣"), FILES("FILES", "▤"),
        MEMORY("MEMORY", "◈"), COMMS("MESSAGES", "✉"), PLATFORM("PLATFORM", "◆"), OTHER("WORK", "•")
    }

    sealed class Target {
        data class Studio(val page: String, val file: String? = null) : Target()
        data class Url(val url: String) : Target()
        data class Page(val id: String) : Target()
        object None : Target()
    }

    private val urlRegex = Regex("https?://[^\\s\"'<>)\\]]+")

    fun parse(args: String): JSONObject? = runCatching { if (args.isBlank()) null else JSONObject(args) }.getOrNull()

    private fun studioPage(action: String): String = when {
        action.startsWith("mcp") || action.startsWith("registry") -> "extensions"
        action == "terminal" -> "terminal"
        action.startsWith("runtime") || action == "ubuntu_install" || action.startsWith("toolchain") -> "runtime"
        action == "packages" -> "packages"
        action == "debug" -> "debug"
        action == "build" || action == "test" || action == "preview" || action == "preview_stop" -> "build"
        action == "git" || action == "git_clone" -> "git"
        action == "dev_task" -> "agent"
        action.startsWith("snapshot") || action == "restore" || action == "rollback" -> "recovery"
        action == "open" -> "editor"
        else -> "editor"
    }

    fun lane(tool: String, args: JSONObject?): Lane {
        val t = tool.lowercase()
        val action = args?.optString("action").orEmpty().lowercase()
        return when {
            t == "studio" -> when (studioPage(action)) {
                "extensions" -> if (action.startsWith("mcp")) Lane.MCP else Lane.PLATFORM
                "terminal", "runtime", "packages" -> Lane.RUNTIME
                else -> Lane.CODE
            }
            t.contains("mcp") -> Lane.MCP
            t in setOf("dev_write", "dev_read", "devspace", "dev_python") -> Lane.CODE
            t in setOf("shell", "python_exec", "execute_system_op", "termux_command", "dev_shell") -> Lane.RUNTIME
            t.startsWith("browser") || t in setOf("web_search", "live_web_search", "fetch_url", "research_web", "open_url", "live_news", "live_sports", "weather", "web_api", "github_public", "github_search_repositories") -> Lane.WEB
            t.startsWith("workspace") || t in setOf("find_files", "open_file", "save_file", "show_file", "document_extract", "backup_list", "backup_restore") -> Lane.FILES
            t in setOf("remember", "recall", "update_memory", "forget", "fact_add", "fact_query", "fact_forget", "knowledge_search", "discussion_search", "decision_history") -> Lane.MEMORY
            t.startsWith("telegram") || t.startsWith("gmail") || t in setOf("sms", "dial", "social", "notion", "google") -> Lane.COMMS
            t in setOf("spawn_worker", "worker_list", "worker_result", "worker_cancel", "platform_selftest", "open_page") -> Lane.PLATFORM
            else -> Lane.OTHER
        }
    }

    /** The place that owns a step, so a tap lands where the work is happening. */
    fun target(tool: String, args: JSONObject?): Target {
        val t = tool.lowercase()
        val action = args?.optString("action").orEmpty().lowercase()
        val path = listOf("file", "path", "name").map { args?.optString(it).orEmpty().trim() }.firstOrNull { it.isNotEmpty() }
        val url = args?.optString("url").orEmpty().trim()
        return when {
            t == "studio" -> Target.Studio(studioPage(action), args?.optString("file")?.takeIf { it.isNotBlank() })
            t in setOf("dev_write", "dev_read", "devspace") -> Target.Studio("editor", path)
            t in setOf("dev_shell", "dev_python", "shell", "python_exec", "execute_system_op", "termux_command") -> Target.Studio("terminal")
            t in setOf("save_file", "open_file", "show_file") && path != null -> Target.Studio("editor", path)
            url.startsWith("http") -> Target.Url(url)
            t in setOf("web_search", "live_web_search", "research_web", "live_news") -> {
                val q = args?.optString("query").orEmpty().ifBlank { args?.optString("q").orEmpty() }.trim()
                if (q.isEmpty()) Target.Page("browser") else Target.Url("https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(q, "UTF-8"))
            }
            t == "spawn_worker" || t.startsWith("worker_") -> Target.Page("tasks")
            else -> OrionNodes.nodeFor(tool, args)?.let { Target.Page(OrionNodes.pageFor(it)) } ?: Target.None
        }
    }

    fun target(step: OrionRunLog.Step): Target = target(step.tool, parse(step.args))
    fun lane(step: OrionRunLog.Step): Lane = lane(step.tool, parse(step.args))

    /** Links found in a step's output (search hits, fetched pages, previews) — each one opens. */
    fun sources(output: String, limit: Int = 8): List<String> =
        urlRegex.findAll(output).map { it.value.trimEnd('.', ',', ';') }.distinct().take(limit).toList()

    fun shortUrl(url: String): String = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").take(48)

    /** What one running task looks like as a single card. */
    data class Summary(
        val title: String, val total: Int, val done: Int, val failed: Int, val running: Int,
        val lanes: List<Pair<Lane, Int>>, val startedAt: Long, val endedAt: Long
    ) {
        val status: String get() = when {
            running > 0 -> "running"
            failed > 0 && done - failed <= 0 -> "failed"
            failed > 0 -> "partial"
            total == 0 -> "idle"
            else -> "done"
        }
    }

    fun summarize(title: String, run: List<OrionRunLog.Step>): Summary {
        val lanes = run.groupBy { lane(it) }.map { it.key to it.value.size }.sortedByDescending { it.second }
        return Summary(
            title.ifBlank { run.firstOrNull()?.title ?: "Task" }, run.size,
            run.count { it.status != OrionRunLog.Status.RUNNING }, run.count { it.status == OrionRunLog.Status.FAILED },
            run.count { it.status == OrionRunLog.Status.RUNNING }, lanes,
            run.minOfOrNull { it.startedAt } ?: 0L, run.maxOfOrNull { it.endedAt } ?: 0L
        )
    }

    /** Failures and anything waiting on the owner are shown apart from the routine steps. */
    fun interruptions(run: List<OrionRunLog.Step>): List<OrionRunLog.Step> =
        run.filter { it.status == OrionRunLog.Status.FAILED || it.tool in setOf("ask_user", "confirm", "approval") }

    fun matches(entry: OrionActivityHistory.Entry, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return entry.title.lowercase().contains(q) || entry.status.contains(q) ||
            entry.steps.any { it.title.lowercase().contains(q) || it.detail.lowercase().contains(q) || it.tool.lowercase().contains(q) }
    }
}

/** Persistent, searchable history of finished, failed and interrupted tasks. */
internal object OrionActivityHistory {
    data class Entry(val id: Long, val title: String, val startedAt: Long, val status: String, val steps: List<OrionRunLog.Step>)

    private const val PREFS = "orion_activity_history_v1"
    private const val KEEP = 60
    @Volatile private var appContext: Context? = null
    @Volatile var runTitle: String = ""

    fun attach(c: Context) { appContext = c.applicationContext }

    private fun stepJson(s: OrionRunLog.Step) = JSONObject()
        .put("id", s.id).put("tool", s.tool).put("title", s.title).put("detail", s.detail).put("node", s.node ?: "")
        .put("start", s.startedAt).put("end", s.endedAt).put("status", s.status.name)
        .put("output", s.output.take(1500)).put("args", s.args.take(800))

    private fun stepFrom(o: JSONObject) = OrionRunLog.Step(
        o.optLong("id"), o.optString("tool"), o.optString("title"), o.optString("detail"),
        o.optString("node").ifBlank { null }, o.optLong("start"), o.optLong("end"),
        runCatching { OrionRunLog.Status.valueOf(o.optString("status")) }.getOrDefault(OrionRunLog.Status.DONE),
        o.optString("output"), o.optString("args")
    )

    fun encode(entries: List<Entry>): String = JSONArray().also { arr ->
        entries.forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("title", e.title).put("start", e.startedAt).put("status", e.status)
                .put("steps", JSONArray().also { a -> e.steps.forEach { a.put(stepJson(it)) } }))
        }
    }.toString()

    fun decode(raw: String): List<Entry> = runCatching {
        val arr = JSONArray(raw)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val st = o.optJSONArray("steps") ?: JSONArray()
            Entry(o.optLong("id"), o.optString("title"), o.optLong("start"), o.optString("status"),
                List(st.length()) { stepFrom(st.getJSONObject(it)) })
        }
    }.getOrDefault(emptyList())

    fun statusOf(run: List<OrionRunLog.Step>): String = OrionActivityHub.summarize("", run).status

    /** Insert or replace this run's entry; the newest first. */
    fun upsert(entries: List<Entry>, run: List<OrionRunLog.Step>, title: String): List<Entry> {
        if (run.isEmpty()) return entries
        val id = run.minOf { it.startedAt }
        val entry = Entry(id, title.ifBlank { run.first().title }.take(120), id, statusOf(run), run)
        return (listOf(entry) + entries.filter { it.id != id }).sortedByDescending { it.startedAt }.take(KEEP)
    }

    /** A stored "running" entry that is no longer live was cut off: show it as interrupted. */
    fun settle(entries: List<Entry>, liveId: Long?): List<Entry> =
        entries.map { if (it.status == "running" && it.id != liveId) it.copy(status = "interrupted") else it }

    @Synchronized fun save(run: List<OrionRunLog.Step>) {
        val c = appContext ?: return
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putString("entries", encode(upsert(decode(p.getString("entries", "") ?: ""), run, runTitle))).apply()
    }

    fun load(liveRun: List<OrionRunLog.Step>): List<Entry> {
        val c = appContext ?: return emptyList()
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("entries", "") ?: ""
        return settle(decode(raw), liveRun.minOfOrNull { it.startedAt })
    }

    fun resumePrompt(e: Entry): String {
        val last = e.steps.lastOrNull { it.status != OrionRunLog.Status.DONE } ?: e.steps.lastOrNull()
        return "Resume the earlier task \"${e.title}\" (${e.status}). " +
            (last?.let { "It stopped at: ${it.title}${if (it.detail.isNotBlank()) " — ${it.detail}" else ""}. " } ?: "") +
            "Pick up from there and finish it."
    }
}
