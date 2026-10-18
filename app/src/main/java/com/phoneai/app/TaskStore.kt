package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small, dependency-free task store. Tasks survive app/process restarts and remain on this device. */
data class OrionTask(
    val id: Int,
    var title: String,
    var details: String,
    var status: String,
    var priority: String,
    var dueAt: Long?,
    val createdAt: Long,
    var updatedAt: Long
)

object TaskStore {
    private val lock = Any()
    private var cache: MutableList<OrionTask>? = null
    private fun file(c: Context) = File(c.filesDir, "orion_tasks.json")

    private fun load(c: Context): MutableList<OrionTask> {
        cache?.let { return it }
        val tasks = mutableListOf<OrionTask>()
        try {
            val f = file(c)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    tasks.add(
                        OrionTask(
                            o.getInt("id"),
                            o.optString("title", ""),
                            o.optString("details", ""),
                            validStatus(o.optString("status", "open")),
                            validPriority(o.optString("priority", "normal")),
                            if (o.isNull("dueAt") || !o.has("dueAt")) null else o.optLong("dueAt"),
                            o.optLong("createdAt", 0L),
                            o.optLong("updatedAt", 0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Keep the app usable if a task file is damaged; do not crash the UI/agent.
        }
        cache = tasks
        return tasks
    }

    private fun validStatus(value: String) =
        if (value.lowercase() in setOf("open", "in_progress", "done", "cancelled")) value.lowercase() else "open"

    private fun validPriority(value: String) =
        if (value.lowercase() in setOf("low", "normal", "high", "urgent")) value.lowercase() else "normal"

    private fun save(c: Context) {
        val tasks = cache ?: return
        val arr = JSONArray()
        tasks.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("title", t.title)
                    .put("details", t.details)
                    .put("status", t.status)
                    .put("priority", t.priority)
                    .put("dueAt", t.dueAt ?: JSONObject.NULL)
                    .put("createdAt", t.createdAt)
                    .put("updatedAt", t.updatedAt)
            )
        }
        try {
            val target = file(c)
            val temp = File(c.filesDir, "orion_tasks.json.tmp")
            temp.writeText(arr.toString())
            if (!temp.renameTo(target)) {
                target.writeText(arr.toString())
                temp.delete()
            }
        } catch (_: Exception) {
            // Storage errors are reported by the caller on the next read/write check.
        }
    }

    fun all(c: Context): List<OrionTask> = synchronized(lock) {
        load(c).sortedWith(compareBy<OrionTask> { it.status == "done" || it.status == "cancelled" }
            .thenBy { it.dueAt ?: Long.MAX_VALUE }.thenByDescending { it.priority == "urgent" })
            .map { it.copy() }
    }

    fun add(c: Context, title: String, details: String = "", priority: String = "normal", dueAt: Long? = null): OrionTask? =
        synchronized(lock) {
            val cleanTitle = title.trim().replace(Regex("\\s+"), " ").take(160)
            if (cleanTitle.isEmpty()) return@synchronized null
            val now = System.currentTimeMillis()
            val tasks = load(c)
            val task = OrionTask(
                (tasks.maxOfOrNull { it.id } ?: 0) + 1,
                cleanTitle,
                details.trim().take(500),
                "open",
                validPriority(priority),
                dueAt?.takeIf { it > 0L },
                now,
                now
            )
            tasks.add(task)
            while (tasks.size > 500) {
                val old = tasks.indexOfFirst { it.status == "done" || it.status == "cancelled" }
                if (old >= 0) tasks.removeAt(old) else break
            }
            save(c)
            task.copy()
        }

    fun update(c: Context, id: Int, title: String? = null, details: String? = null,
               status: String? = null, priority: String? = null, dueAt: Long? = null,
               clearDueAt: Boolean = false): Boolean = synchronized(lock) {
        val task = load(c).firstOrNull { it.id == id } ?: return@synchronized false
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { task.title = it.replace(Regex("\\s+"), " ").take(160) }
        details?.let { task.details = it.trim().take(500) }
        status?.let { task.status = validStatus(it) }
        priority?.let { task.priority = validPriority(it) }
        if (clearDueAt) task.dueAt = null else if (dueAt != null && dueAt > 0L) task.dueAt = dueAt
        task.updatedAt = System.currentTimeMillis()
        save(c)
        true
    }

    fun delete(c: Context, id: Int): Boolean = synchronized(lock) {
        val removed = load(c).removeAll { it.id == id }
        if (removed) save(c)
        removed
    }
}
