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
    var updatedAt: Long,
    var resultSummary: String = ""
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
                            o.optLong("updatedAt", 0L),
                            o.optString("resultSummary", "").take(1200)
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

    private fun validStatus(value: String): String = when (value.trim().lowercase()) {
        "open", "queued" -> "queued"
        "in_progress", "running" -> "running"
        "done", "completed" -> "completed"
        "paused" -> "paused"
        "failed" -> "failed"
        "cancelled", "canceled" -> "cancelled"
        else -> "queued"
    }

    private fun validPriority(value: String) =
        if (value.lowercase() in setOf("low", "normal", "high", "urgent")) value.lowercase() else "normal"

    /** Persist through a temporary file and report failure instead of silently claiming success. */
    private fun save(c: Context): Boolean {
        val tasks = cache ?: return false
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
                    .put("resultSummary", t.resultSummary.take(1200))
            )
        }
        val target = file(c)
        val temp = File(c.filesDir, "orion_tasks.json.tmp")
        return try {
            temp.writeText(arr.toString())
            if (temp.renameTo(target)) {
                true
            } else {
                target.writeText(arr.toString())
                temp.delete()
                true
            }
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    private fun copyOf(t: OrionTask) = t.copy()

    fun all(c: Context): List<OrionTask> = synchronized(lock) {
        load(c).sortedWith(compareBy<OrionTask> { it.status in setOf("completed", "done", "failed", "cancelled") }
            .thenByDescending { it.priority == "urgent" }
            .thenBy { it.dueAt ?: Long.MAX_VALUE })
            .map { copyOf(it) }
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
                "queued",
                validPriority(priority),
                dueAt?.takeIf { it > 0L },
                now,
                now
            )
            tasks.add(task)
            val removed = mutableListOf<OrionTask>()
            while (tasks.size > 500) {
                val old = tasks.indexOfFirst { it.status in setOf("completed", "done", "failed", "cancelled") }
                if (old >= 0) removed.add(tasks.removeAt(old)) else break
            }
            if (!save(c)) {
                tasks.removeAll { it.id == task.id }
                tasks.addAll(removed)
                return@synchronized null
            }
            TaskReminder.schedule(c, task)
            copyOf(task)
        }

    fun update(c: Context, id: Int, title: String? = null, details: String? = null,
               status: String? = null, priority: String? = null, dueAt: Long? = null,
               clearDueAt: Boolean = false, resultSummary: String? = null): Boolean = synchronized(lock) {
        val task = load(c).firstOrNull { it.id == id } ?: return@synchronized false
        val before = copyOf(task)
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { task.title = it.replace(Regex("\\s+"), " ").take(160) }
        details?.let { task.details = it.trim().take(500) }
        status?.let { task.status = validStatus(it) }
        priority?.let { task.priority = validPriority(it) }
        if (clearDueAt) task.dueAt = null else if (dueAt != null && dueAt > 0L) task.dueAt = dueAt
        resultSummary?.let { task.resultSummary = it.take(1200) }
        task.updatedAt = System.currentTimeMillis()
        if (!save(c)) {
            task.title = before.title
            task.details = before.details
            task.status = before.status
            task.priority = before.priority
            task.dueAt = before.dueAt
            task.updatedAt = before.updatedAt
            task.resultSummary = before.resultSummary
            return@synchronized false
        }
        TaskReminder.schedule(c, copyOf(task))
        true
    }

    fun delete(c: Context, id: Int): Boolean = synchronized(lock) {
        val tasks = load(c)
        val removed = tasks.filter { it.id == id }
        if (removed.isEmpty()) return@synchronized false
        tasks.removeAll { it.id == id }
        if (!save(c)) {
            tasks.addAll(removed)
            return@synchronized false
        }
        TaskReminder.cancel(c, id)
        true
    }
}
