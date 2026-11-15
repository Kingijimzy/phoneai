package com.phoneai.app

import android.content.Context

/**
 * Code lives in the workspace, not the chat. Long fenced blocks in an answer are saved as files and replaced by a
 * short card line; the full text is still one tap away on the Workspace page.
 */
internal object OrionCodeOffload {
    const val MIN_LINES = 9
    private val fence = Regex("```([A-Za-z0-9_+#.-]*)[ \\t]*\\n([\\s\\S]*?)```")

    private fun ext(lang: String): String = when (lang.lowercase()) {
        "kotlin", "kt" -> "kt"; "python", "py" -> "py"; "javascript", "js" -> "js"; "typescript", "ts" -> "ts"
        "java" -> "java"; "bash", "sh", "shell", "zsh" -> "sh"; "html" -> "html"; "css" -> "css"; "json" -> "json"
        "yaml", "yml" -> "yml"; "xml" -> "xml"; "sql" -> "sql"; "go" -> "go"; "rust", "rs" -> "rs"; "c" -> "c"
        "cpp", "c++" -> "cpp"; "markdown", "md" -> "md"; "" -> "txt"; else -> lang.lowercase().take(6)
    }

    /** [save] writes a file and returns true on success; kept injectable so this stays pure and testable. */
    fun offload(text: String, stamp: String, save: (path: String, content: String) -> Boolean): String {
        var n = 0
        return fence.replace(text) { m ->
            val lang = m.groupValues[1]
            val body = m.groupValues[2].trimEnd()
            val lines = body.lines().size
            if (lines < MIN_LINES) m.value else {
                n++
                val path = "orion_chat/$stamp-$n.${ext(lang)}"
                if (save(path, body)) "\n▣ **Code saved to workspace** · `$path` · $lines lines${if (lang.isNotBlank()) " · $lang" else ""} — open it from the Workspace page.\n" else m.value
            }
        }
    }

    fun offload(c: Context, text: String): String {
        if (!text.contains("```")) return text
        val stamp = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val ws = OrionPythonWorkspace(c)
        return offload(text, stamp) { path, content -> runCatching { !ws.write(path, content).startsWith("error", true) }.getOrDefault(false) }
    }
}
