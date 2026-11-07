package com.phoneai.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Conflict-safe undo journal for app-private workspace files.
 * Snapshots are bounded, hash-checked against the live file before reversal, and never
 * restore over a file that has changed since the recorded operation.
 */
internal class WorkspaceUndoStore(context: Context) {
    private val lock = Any()
    private val workspace = OrionPythonWorkspace(context.applicationContext)
    private val file = AtomicFile(File(context.applicationContext.filesDir, "orion_workspace_undo.json"))

    companion object {
        private const val MAX_ENTRIES = 10
        private const val MAX_SNAPSHOT_BYTES = OrionPythonWorkspace.MAX_FILE_BYTES
    }

    fun recordWrite(path: String, before: String?, after: String): Boolean = synchronized(lock) {
        if (!validSnapshot(before) || !validSnapshot(after)) return@synchronized false
        append(JSONObject().put("operation", if (before == null) "workspace_create" else "workspace_write")
            .put("path", path).put("beforeExists", before != null)
            .put("before", before ?: JSONObject.NULL).put("after", after).put("at", System.currentTimeMillis()))
    }

    fun recordDelete(path: String, before: String, trashPath: String? = null): Boolean = synchronized(lock) {
        if (!validSnapshot(before)) return@synchronized false
        append(JSONObject().put("operation", "workspace_delete").put("path", path)
            .put("beforeExists", true).put("before", before).put("afterExists", false)
            .put("trashPath", trashPath ?: JSONObject.NULL)
            .put("at", System.currentTimeMillis()))
    }

    fun recordMove(source: String, destination: String, content: String): Boolean = synchronized(lock) {
        if (!validSnapshot(content) || source == destination) return@synchronized false
        append(JSONObject().put("operation", "workspace_move").put("path", source)
            .put("destination", destination).put("before", content).put("after", content)
            .put("at", System.currentTimeMillis()))
    }

    fun latestTimestamp(): Long = synchronized(lock) {
        val rows = readRows() ?: return@synchronized Long.MAX_VALUE
        rows.lastOrNull()?.optLong("at", 0L) ?: 0L
    }

    fun status(): String = synchronized(lock) {
        val rows = readRows() ?: return@synchronized "Workspace undo history is unreadable; no action was changed."
        val last = rows.lastOrNull() ?: return@synchronized "No reversible workspace file change is recorded."
        "Last reversible workspace change: ${last.optString("operation").replace('_', ' ')} at ${last.optString("path")}. Undo checks the live file before restoring it."
    }

    /** Returns null only when no workspace entry exists, so callers may try memory undo. */
    fun undoLast(): String? = synchronized(lock) {
        val rows = readRows() ?: return@synchronized "Workspace undo history is unreadable; no action was changed."
        val entry = rows.lastOrNull() ?: return@synchronized null
        val operation = entry.optString("operation")
        val path = entry.optString("path")
        val ok = when (operation) {
            "workspace_create", "workspace_write" -> {
                val current = workspace.readForUndo(path)
                val expected = entry.optString("after")
                if (current != expected) false
                else if (entry.optBoolean("beforeExists")) {
                    val before = entry.optString("before")
                    workspace.write(path, before).startsWith("saved ") && workspace.readForUndo(path) == before
                } else workspace.delete(path) && workspace.readForUndo(path) == null
            }
            "workspace_delete" -> {
                val before = entry.optString("before")
                val trashPath = entry.optString("trashPath", null)
                val restored = if (trashPath != null && trashPath.isNotBlank()) {
                    workspace.restoreFromTrash(path).startsWith("restored ") && workspace.readForUndo(path) == before
                } else {
                    workspace.readForUndo(path) == null &&
                        workspace.write(path, before).startsWith("saved ") && workspace.readForUndo(path) == before
                }
                restored
            }
            "workspace_move" -> {
                val destination = entry.optString("destination")
                val content = entry.optString("after")
                workspace.readForUndo(path) == null && workspace.readForUndo(destination) == content &&
                    workspace.move(destination, path).startsWith("moved ") &&
                    workspace.readForUndo(path) == content && workspace.readForUndo(destination) == null
            }
            else -> false
        }
        if (!ok) return@synchronized "Could not safely undo the workspace change: the file state no longer matches the saved receipt. No data was overwritten."
        rows.removeAt(rows.lastIndex)
        if (!persist(rows)) return@synchronized "Workspace change was reversed, but the undo receipt could not be updated. Check the workspace before another undo."
        "Undid the last workspace file change successfully."
    }

    private fun validSnapshot(value: String?): Boolean =
        value == null || value.toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_BYTES

    private fun append(entry: JSONObject): Boolean {
        val rows = readRows() ?: return false
        rows += entry
        while (rows.size > MAX_ENTRIES) rows.removeAt(0)
        return persist(rows)
    }

    private fun readRows(): MutableList<JSONObject>? {
        val raw = try { file.openRead().bufferedReader().use { it.readText() } }
        catch (_: java.io.FileNotFoundException) { return mutableListOf() }
        val root = try { JSONObject(raw) } catch (_: Exception) { return null }
        if (root.optInt("version", -1) != 1) return null
        val array = root.optJSONArray("entries") ?: return null
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.toMutableList()
    }

    private fun persist(rows: List<JSONObject>): Boolean {
        val array = JSONArray()
        rows.takeLast(MAX_ENTRIES).forEach { array.put(it) }
        val bytes = JSONObject().put("version", 1).put("entries", array).toString().toByteArray(Charsets.UTF_8)
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
            true
        } catch (_: Exception) {
            val failedStream = stream
            if (failedStream != null) runCatching { file.failWrite(failedStream) }
            false
        }
    }
}
