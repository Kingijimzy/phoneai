package com.phoneai.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Device-side durable lease ledger for Orion's local worker adapter.
 *
 * The monotonically increasing fencing token prevents a worker whose lease expired
 * from acknowledging work after a newer worker has acquired it. Completion is
 * idempotent by task ID + idempotency key. This ledger is not a substitute for the
 * server-side transactional store when remote workers are enabled.
 */
internal class OrionWorkerLeaseStore(context: Context, private val clock: () -> Long = { System.currentTimeMillis() }) {
    data class Lease(
        val taskId: String,
        val workerId: String,
        val leaseId: String,
        val fencingToken: Long,
        val expiresAt: Long,
        val heartbeatAt: Long,
        val idempotencyKey: String
    )
    data class Completion(val taskId: String, val idempotencyKey: String, val resultRef: String, val completedAt: Long)

    private val lock = Any()
    private val file = File(context.applicationContext.filesDir, "orion_worker_leases.json")
    private val atomic = AtomicFile(file)

    fun acquire(task: OrionTaskEnvelope, workerId: String, leaseDurationMs: Long): Lease? = synchronized(lock) {
        if (task.validate(clock()).isNotEmpty() || !SAFE_WORKER.matches(workerId) ||
            leaseDurationMs !in MIN_LEASE_MS..MAX_LEASE_MS) return@synchronized null
        val root = read()
        val completions = root.optJSONObject("completions") ?: JSONObject()
        val completed = completions.optJSONObject(task.taskId)
        if (completed != null) {
            return@synchronized if (completed.optString("idempotencyKey") == task.idempotencyKey) null else null
        }
        val leases = root.optJSONObject("leases") ?: JSONObject()
        val current = leases.optJSONObject(task.taskId)
        if (current != null && current.optLong("expiresAt") > clock()) return@synchronized null
        val previousToken = current?.optLong("fencingToken", 0L)
            ?: root.optJSONObject("fences")?.optLong(task.taskId, 0L) ?: 0L
        val token = previousToken + 1L
        val lease = Lease(task.taskId, workerId, UUID.randomUUID().toString(), token,
            clock() + leaseDurationMs, clock(), task.idempotencyKey)
        leases.put(task.taskId, lease.toJson())
        root.put("leases", leases)
        val fences = root.optJSONObject("fences") ?: JSONObject()
        fences.put(task.taskId, token)
        root.put("fences", fences)
        if (!write(root)) return@synchronized null
        lease
    }

    fun heartbeat(lease: Lease, extensionMs: Long): Lease? = synchronized(lock) {
        if (extensionMs !in MIN_LEASE_MS..MAX_LEASE_MS) return@synchronized null
        val root = read()
        val leases = root.optJSONObject("leases") ?: return@synchronized null
        val current = leases.optJSONObject(lease.taskId) ?: return@synchronized null
        if (!matches(current, lease) || current.optLong("expiresAt") <= clock()) return@synchronized null
        val updated = lease.copy(heartbeatAt = clock(), expiresAt = clock() + extensionMs)
        leases.put(lease.taskId, updated.toJson())
        root.put("leases", leases)
        if (!write(root)) null else updated
    }

    /** Returns true for a new completion and for an identical replay; rejects stale fences. */
    fun complete(lease: Lease, resultRef: String): Boolean = synchronized(lock) {
        if (!SAFE_REF.matches(resultRef)) return@synchronized false
        val root = read()
        val completions = root.optJSONObject("completions") ?: JSONObject()
        val existing = completions.optJSONObject(lease.taskId)
        if (existing != null) return@synchronized existing.optString("idempotencyKey") == lease.idempotencyKey &&
            existing.optString("resultRef") == resultRef
        val leases = root.optJSONObject("leases") ?: return@synchronized false
        val current = leases.optJSONObject(lease.taskId) ?: return@synchronized false
        if (!matches(current, lease) || current.optLong("expiresAt") <= clock()) return@synchronized false
        completions.put(lease.taskId, JSONObject()
            .put("idempotencyKey", lease.idempotencyKey)
            .put("resultRef", resultRef)
            .put("completedAt", clock()))
        root.put("completions", completions)
        leases.remove(lease.taskId)
        root.put("leases", leases)
        write(root)
    }

    /** Durable completion reference used to reconcile the task ledger after process death. */\n    fun completedResult(taskId: String, idempotencyKey: String): String? = synchronized(lock) {\n        val item = read().optJSONObject("completions")?.optJSONObject(taskId) ?: return@synchronized null\n        item.optString("resultRef").takeIf { item.optString("idempotencyKey") == idempotencyKey && it.isNotBlank() }\n    }\n\n    fun isCompleted(taskId: String, idempotencyKey: String): Boolean = synchronized(lock) {
        val item = read().optJSONObject("completions")?.optJSONObject(taskId) ?: return@synchronized false
        item.optString("idempotencyKey") == idempotencyKey
    }

    fun release(lease: Lease): Boolean = synchronized(lock) {
        val root = read()
        val leases = root.optJSONObject("leases") ?: return@synchronized false
        val current = leases.optJSONObject(lease.taskId) ?: return@synchronized false
        if (!matches(current, lease)) return@synchronized false
        leases.remove(lease.taskId)
        root.put("leases", leases)
        write(root)
    }

    private fun matches(json: JSONObject, lease: Lease): Boolean =
        json.optString("workerId") == lease.workerId &&
            json.optString("leaseId") == lease.leaseId &&
            json.optLong("fencingToken") == lease.fencingToken &&
            json.optString("idempotencyKey") == lease.idempotencyKey

    private fun Lease.toJson() = JSONObject()
        .put("workerId", workerId).put("leaseId", leaseId)
        .put("fencingToken", fencingToken).put("expiresAt", expiresAt)
        .put("heartbeatAt", heartbeatAt).put("idempotencyKey", idempotencyKey)

    private fun read(): JSONObject = try {
        JSONObject(atomic.openRead().bufferedReader().use { it.readText() })
    } catch (_: Exception) { JSONObject() }

    private fun write(root: JSONObject): Boolean {
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = atomic.startWrite()
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            true
        } catch (_: Exception) {
            stream?.let { runCatching { atomic.failWrite(it) } }
            false
        }
    }

    companion object {
        private const val MIN_LEASE_MS = 1_000L
        private const val MAX_LEASE_MS = 24L * 60L * 60L * 1000L
        private val SAFE_WORKER = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$")
        private val SAFE_REF = Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$")
    }
}
