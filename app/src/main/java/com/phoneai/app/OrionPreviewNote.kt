package com.phoneai.app

import org.json.JSONObject
import java.io.File

/** Turns a successful write or show of an HTML page into a live in-chat preview card. */
internal object OrionPreviewNote {
    private val pageTools = setOf("workspace_write", "dev_write", "show_file", "save_file")

    fun build(filesDir: File, tool: String, args: JSONObject, failed: Boolean): String? {
        if (failed || tool !in pageTools) return null
        val path = args.optString("path").trim()
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext != "html" && ext != "htm") return null
        val root = File(filesDir, "orion-python/workspace")
        val file = listOf(File(root, path.removePrefix("/workspace/").removePrefix("/")), File(path))
            .firstOrNull { it.isFile && it.canRead() && it.length() <= 600_000 } ?: return null
        return "[PREVIEW]\n${file.absolutePath}\n${path.substringAfterLast('/')}"
    }
}
