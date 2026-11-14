package com.phoneai.app

import org.json.JSONObject

/**
 * Turns tool calls into the short first-person lines a companion would say while working
 * ("Searching the web for …", "Reading Agent.kt."). Pure logic; delivery is the UI's job.
 */
internal object OrionNarrator {
    private fun arg(a: JSONObject?, vararg keys: String): String =
        keys.firstNotNullOfOrNull { k -> a?.optString(k, "")?.trim()?.takeIf { it.isNotBlank() } }.orEmpty()

    private fun clip(s: String, n: Int = 46) = s.replace(Regex("\\s+"), " ").let { if (it.length > n) it.take(n - 1).trimEnd() + "…" else it }

    /** Category lets the tracker stay quiet while consecutive calls do the same kind of work. */
    fun category(tool: String): String = when {
        Regex("search|news|web_|research|lookup").containsMatchIn(tool) -> "search"
        Regex("browser|open_url|navigate|screenshot_page").containsMatchIn(tool) -> "browse"
        Regex("shell|terminal|dev_|bash|run_|python|build|test").containsMatchIn(tool) -> "run"
        Regex("write|edit|patch|create_file|extension_|code").containsMatchIn(tool) -> "write"
        Regex("read|list_dir|grep|find|file").containsMatchIn(tool) -> "read"
        Regex("image|draw|vision|camera|photo").containsMatchIn(tool) -> "image"
        Regex("memory|remember|recall").containsMatchIn(tool) -> "memory"
        else -> "act"
    }

    fun line(tool: String, args: JSONObject?): String? {
        val subject = clip(arg(args, "query", "q", "url", "path", "file", "name", "command", "prompt", "text"))
        return when (category(tool)) {
            "search" -> if (subject.isNotBlank()) "Searching for $subject." else "Searching that now."
            "browse" -> if (subject.isNotBlank()) "Opening $subject." else "Opening the page."
            "run" -> "Running that and checking the result."
            "write" -> if (subject.isNotBlank()) "Writing changes to $subject." else "Writing the code now."
            "read" -> if (subject.isNotBlank()) "Reading $subject." else "Looking through the files."
            "image" -> "Working on the image."
            "memory" -> "Checking what I remember."
            else -> null
        }
    }

    /** Speak only when the kind of work changes, at most [max] lines per owner turn. */
    class Tracker(private val max: Int = 4) {
        private var last: String? = null
        private var spoken = 0
        fun next(tool: String, args: JSONObject?): String? {
            val cat = category(tool)
            if (cat == last) return null
            last = cat
            if (spoken >= max) return null
            val l = line(tool, args) ?: return null
            spoken++
            return l
        }
    }
}
