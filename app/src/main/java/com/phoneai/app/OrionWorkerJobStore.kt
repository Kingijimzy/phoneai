package com.phoneai.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.UUID

/**
 * Durable ledger for independently scheduled specialist jobs.
 *
 * This is deliberately a job ledger, not an executor: it never calls a model, invokes an Android
 * action, or bypasses the parent Agent. Only analysis/read-only job classes are representable.
 * A future worker executor must claim through this store and re-check the exact parent checkpoint
 * immediately before provider work.
 */
internal class OrionWorkerJobStore(context: Context, private val clock: () -> Long = { System.currentTimeMillis() }) {
    enum class Kind { ANALYSIS, READ_ONLY_RESEARCH, TRADING_ANALYSIS, TASK_PLANNING }
    enum class Status { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED, NEEDS_REVIEW }

    data class Job(
        val id: String,
        val idempotencyKey: String,
        val parentTaskId: String,
        val parentObjectiveId: String?,
        val kind: Kind,
        val objective: String,
        val status: Status,
        val createdAt: Long,
        val updatedAt: Long,
        val attempts: Int,
        val result: String = "",
        val lastError: String = ""
    )

    private val app = context.applicationContext
    private val lock = Any()
    private val file = File(app.filesDir, "orion_worker_jobs.json")
    private val atomic = AtomicFile(file)
    private val maxJobs = 32
    private val maxActive = 1
    private var jobs = mutableListOf<Job>()

    init { synchronized(lock) { loadLocked() } }

    /** Duplicate keys return the original job; they never create a second provider charge. */
    fun enqueue(
        parentTaskId: String,
        objective: String,
        kind: Kind = Kind.ANALYSIS,
        idempotencyKey: String = UUID.randomUUID().toString(),
        parentObjectiveId: String? = null
    ): Job? = synchronized(lock) {
        val parent = parentTaskId.trim().take(80)
        val key = idempotencyKey.trim().take(100)
        val clean = AgentPrivacyFilter.safeCommand(objective).replace(Regex("\\s+"), " ").trim().take(700)
        if (parent.isBlank() || key.isBlank() || clean.isBlank()) return@synchronized null
        jobs.firstOrNull { it.parentTaskId == parent && it.idempotencyKey == key }?.let { return@synchronized it }
        if (jobs.size >= maxJobs) {
            val removable = jobs.indexOfFirst { it.status in TERMINAL }
            if (removable < 0) return@synchronized null
            jobs.removeAt(removable)
        }
        val now = clock()
        val job = Job(UUID.randomUUID().toString(), key, parent,
            parentObjectiveId?.trim()?.takeIf { Regex("^[a-z][a-z0-9_-]{0,31}$").matches(it) },
            kind, clean, Status.QUEUED, now, now, 0)
        jobs.add(job)
        if (!persistLocked()) { jobs.remove(job); return@synchronized null }
        job
    }

    /**
     * Claims at most one job. A missing, paused, stopped or replaced parent fails closed and
     * cancels that queued child rather than allowing an orphan to execute.
     */
    fun claimNext(parentIsRunning: (String) -> Boolean): Job? = synchronized(lock) {
        if (jobs.count { it.status == Status.RUNNING } >= maxActive) return@synchronized null
        val next = jobs.firstOrNull { it.status == Status.QUEUED } ?: return@synchronized null
        if (!runCatching { parentIsRunning(next.parentTaskId) }.getOrDefault(false)) {
            replaceLocked(next, next.copy(status = Status.CANCELLED, updatedAt = clock(),
                lastError = "parent_not_running_or_unverifiable"))
            persistLocked()
            return@synchronized null
        }
        val running = next.copy(status = Status.RUNNING, updatedAt = clock(), attempts = next.attempts + 1)
        if (!replaceLocked(next, running)) return@synchronized null
        if (!persistLocked()) {
            replaceLocked(running, next)
            return@synchronized null
        }
        running
    }

    fun complete(jobId: String, result: String): Boolean = transition(jobId, setOf(Status.RUNNING)) {
        it.copy(status = Status.COMPLETED, updatedAt = clock(),
            result = AgentPrivacyFilter.safeCommand(result).replace(Regex("\\s+"), " ").take(1200), lastError = "")
    }

    fun fail(jobId: String, reason: String): Boolean = transition(jobId, setOf(Status.RUNNING)) {
        it.copy(status = Status.FAILED, updatedAt = clock(),
            lastError = AgentPrivacyFilter.safeCommand(reason).replace(Regex("\\s+"), " ").take(240))
    }

    fun cancel(jobId: String, reason: String = "cancelled_by_parent"): Boolean = transition(
        jobId, setOf(Status.QUEUED, Status.RUNNING, Status.PAUSED)
    ) {
        it.copy(status = Status.CANCELLED, updatedAt = clock(),
            lastError = AgentPrivacyFilter.safeCommand(reason).take(240))
    }

    fun cancelParent(parentTaskId: String, reason: String = "parent_cancelled"): Int = synchronized(lock) {
        val parent = parentTaskId.trim()
        if (parent.isBlank()) return@synchronized 0
        val safeReason = AgentPrivacyFilter.safeCommand(reason).replace(Regex("\\s+"), " ").take(120)
        val before = jobs.toList()
        var changed = 0
        jobs = jobs.map { job ->
            if (job.parentTaskId == parent && job.status in setOf(Status.QUEUED, Status.RUNNING, Status.PAUSED)) {
                changed++
                job.copy(status = Status.CANCELLED, updatedAt = clock(), lastError = safeReason)
            } else job
        }.toMutableList()
        if (changed > 0 && !persistLocked()) { jobs = before.toMutableList(); return@synchronized 0 }
        changed
    }

    /** A running provider call may have completed before process death; never blindly replay it. */
    fun all(): List<Job> = synchronized(lock) { jobs.map { it.copy() } }

    private fun transition(jobId: String, allowed: Set<Status>, update: (Job) -> Job): Boolean =
        synchronized(lock) {
            val current = jobs.firstOrNull { it.id == jobId && it.status in allowed } ?: return@synchronized false
            val changed = update(current)
            if (!replaceLocked(current, changed)) return@synchronized false
            if (!persistLocked()) { replaceLocked(changed, current); return@synchronized false }
            true
        }

    private fun replaceLocked(old: Job, changed: Job): Boolean {
        val index = jobs.indexOfFirst { it.id == old.id }
        if (index < 0) return false
        jobs[index] = changed
        return true
    }

    private fun loadLocked() {
        val root = try { JSONObject(atomic.openRead().bufferedReader().use { it.readText() }) }
        catch (_: FileNotFoundException) { return }
        catch (_: Exception) { return }
        val array = root.optJSONArray("jobs") ?: JSONArray()
        jobs = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrNull() ?: return@mapNotNull null
            val status = runCatching { Status.valueOf(o.optString("status")) }.getOrNull() ?: return@mapNotNull null
            val id = o.optString("id").take(80)
            val parent = o.optString("parentTaskId").take(80)
            val key = o.optString("idempotencyKey").take(100)
            if (id.isBlank() || parent.isBlank() || key.isBlank()) return@mapNotNull null
            val restoredStatus = if (status == Status.RUNNING) Status.NEEDS_REVIEW else status
            Job(id, key, parent, o.optString("parentObjectiveId").takeIf { it.isNotBlank() && it != "null" },
                kind, o.optString("objective").take(700), restoredStatus,
                o.optLong("createdAt"), clock(), o.optInt("attempts").coerceIn(0, 3),
                o.optString("result").take(1200), if (status == Status.RUNNING)
                    "process_interrupted_outcome_must_be_reviewed" else o.optString("lastError").take(240))
        }.takeLast(maxJobs).toMutableList()
        if (jobs.any { it.status == Status.NEEDS_REVIEW }) persistLocked()
    }

    private fun persistLocked(): Boolean {
        val array = JSONArray()
        jobs.forEach { j ->
            array.put(JSONObject().put("id", j.id).put("idempotencyKey", j.idempotencyKey)
                .put("parentTaskId", j.parentTaskId).put("parentObjectiveId", j.parentObjectiveId)
                .put("kind", j.kind.name).put("objective", j.objective).put("status", j.status.name)
                .put("createdAt", j.createdAt).put("updatedAt", j.updatedAt).put("attempts", j.attempts)
                .put("result", j.result).put("lastError", j.lastError))
        }
        var stream: FileOutputStream? = null
        return try {
            stream = atomic.startWrite()
            stream.write(JSONObject().put("jobs", array).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            true
        } catch (_: Exception) {
            stream?.let { runCatching { atomic.failWrite(it) } }
            false
        }
    }

    companion object {
        private val TERMINAL = setOf(Status.COMPLETED, Status.FAILED, Status.CANCELLED)
    }
}
