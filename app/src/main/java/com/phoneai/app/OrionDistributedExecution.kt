package com.phoneai.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Durable execution ledger for delegated work; separate from the user's reminder/task list. */
internal class OrionDistributedTaskStore(context: Context, private val clock: () -> Long = { System.currentTimeMillis() }) {
    data class Record(val envelope: OrionTaskEnvelope, val status: String, val resultRef: String?, val updatedAt: Long)
    private val lock = Any()
    private val atomic = AtomicFile(File(context.applicationContext.filesDir, "orion_distributed_tasks.json"))

    fun enqueue(task: OrionTaskEnvelope): Boolean = synchronized(lock) {
        if (task.validate(clock()).isNotEmpty()) return@synchronized false
        val root = read()
        val rows = root.optJSONObject("tasks") ?: JSONObject()
        if (rows.has(task.taskId) || rows.length() >= MAX_TASKS) return@synchronized false
        if (task.parentTaskId != null) {
            val parent = rows.optJSONObject(task.parentTaskId) ?: return@synchronized false
            if (parent.optString("status") in TERMINAL) return@synchronized false
            if (parent.optString("rootTaskId") != task.rootTaskId) return@synchronized false
        } else if (task.rootTaskId != task.taskId) return@synchronized false
        rows.put(task.taskId, JSONObject().put("envelope", task.toJson()).put("status", "queued")
            .put("resultRef", JSONObject.NULL).put("updatedAt", clock()))
        root.put("tasks", rows)
        write(root)
    }

    fun createChild(parentId: String, objective: String, kind: OrionTaskEnvelope.Kind,
                    capabilities: List<String> = emptyList()): OrionTaskEnvelope? = synchronized(lock) {
        val parent = record(read().optJSONObject("tasks")?.optJSONObject(parentId)) ?: return@synchronized null
        if (parent.status in TERMINAL || parent.envelope.validate(clock()).isNotEmpty()) return@synchronized null
        val child = OrionTaskEnvelope.create(objective, kind, parent.envelope.ownerScope, capabilities,
            parentTaskId = parentId, rootTaskId = parent.envelope.rootTaskId,
            deadlineAt = parent.envelope.deadlineAt, policyVersion = parent.envelope.policyVersion, now = clock())
        if (enqueue(child)) child else null
    }

    fun claim(taskId: String): Boolean = transition(taskId, setOf("queued", "retry_wait"), "running")
    fun complete(taskId: String, resultRef: String): Boolean = synchronized(lock) {
        if (!SAFE_REF.matches(resultRef)) return@synchronized false
        val root = read(); val rows = root.optJSONObject("tasks") ?: return@synchronized false
        val row = rows.optJSONObject(taskId) ?: return@synchronized false
        if (row.optString("status") == "completed") return@synchronized row.optString("resultRef") == resultRef
        if (row.optString("status") != "running") return@synchronized false
        row.put("status", "completed").put("resultRef", resultRef).put("updatedAt", clock())
        rows.put(taskId, row); root.put("tasks", rows)
        write(root)
    }

    fun fail(taskId: String, retryable: Boolean): Boolean =
        transition(taskId, setOf("running"), if (retryable) "retry_wait" else "failed")

    fun cancel(taskId: String, reason: String = "owner_requested"): Int = synchronized(lock) {
        val root = read(); val rows = root.optJSONObject("tasks") ?: return@synchronized 0
        val descendants = descendants(rows, taskId)
        if (!rows.has(taskId)) return@synchronized 0
        var changed = 0
        (listOf(taskId) + descendants).forEach { id ->
            val row = rows.optJSONObject(id) ?: return@forEach
            if (row.optString("status") !in TERMINAL) {
                row.put("status", if (row.optString("status") == "running") "cancel_requested" else "cancelled")
                    .put("cancelReason", reason.take(120)).put("updatedAt", clock())
                rows.put(id, row); changed++
            }
        }
        root.put("tasks", rows)
        if (changed > 0 && !write(root)) 0 else changed
    }

    fun isCancellationRequested(taskId: String): Boolean = synchronized(lock) {
        read().optJSONObject("tasks")?.optJSONObject(taskId)?.optString("status") in setOf("cancel_requested", "cancelled")
    }

    fun markRecovered(taskId: String): Boolean = transition(taskId, setOf("cancel_requested"), "cancelled")
    fun get(taskId: String): Record? = synchronized(lock) { record(read().optJSONObject("tasks")?.optJSONObject(taskId)) }
    fun all(): List<Record> = synchronized(lock) {
        val rows = read().optJSONObject("tasks") ?: return@synchronized emptyList()
        rows.keys().asSequence().mapNotNull { record(rows.optJSONObject(it)) }.sortedBy { it.updatedAt }.toList()
    }

    /** Requeues only expired/known-safe work; running work is never blindly replayed. */
    fun recoverQueued(): List<String> = synchronized(lock) {
        val rows = read().optJSONObject("tasks") ?: return@synchronized emptyList()
        rows.keys().asSequence().mapNotNull { id ->
            val row = rows.optJSONObject(id) ?: return@mapNotNull null
            if (row.optString("status") == "cancel_requested") {
                row.put("status", "cancelled").put("updatedAt", clock()); id
            } else null
        }.toList().also { if (it.isNotEmpty()) { val root = read().put("tasks", rows); write(root) } }
    }

    fun summary(): String = synchronized(lock) {
        val records = all()
        "delegated_tasks=${records.size}; queued=${records.count { it.status == "queued" }}; running=${records.count { it.status == "running" }}; recovery_required=${records.count { it.status == "cancel_requested" }}; failed=${records.count { it.status == "failed" }}"
    }

    private fun transition(id: String, from: Set<String>, to: String): Boolean = synchronized(lock) {
        val root = read(); val rows = root.optJSONObject("tasks") ?: return@synchronized false
        val row = rows.optJSONObject(id) ?: return@synchronized false
        if (row.optString("status") !in from) return@synchronized false
        row.put("status", to).put("updatedAt", clock()); rows.put(id, row); root.put("tasks", rows); write(root)
    }
    private fun descendants(rows: JSONObject, parentId: String): List<String> {
        val out = mutableListOf<String>(); val queue = ArrayDeque<String>(); queue.add(parentId)
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            rows.keys().forEach { id ->
                val env = rows.optJSONObject(id)?.optJSONObject("envelope") ?: return@forEach
                if (env.optString("parentTaskId") == parent) { out += id; queue.add(id) }
            }
        }
        return out
    }
    private fun record(row: JSONObject?): Record? = row?.let {
        runCatching { Record(OrionTaskEnvelope.fromJson(it.getJSONObject("envelope")), it.optString("status"),
            it.optString("resultRef").takeUnless { v -> v == "null" || v.isBlank() }, it.optLong("updatedAt")) }.getOrNull()
    }
    private fun read(): JSONObject = try { JSONObject(atomic.openRead().bufferedReader().use { it.readText() }) } catch (_: Exception) { JSONObject() }
    private fun write(root: JSONObject): Boolean {
        var stream: java.io.FileOutputStream? = null
        return try { stream = atomic.startWrite(); stream.write(root.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream); true }
        catch (_: Exception) { stream?.let { runCatching { atomic.failWrite(it) } }; false }
    }
    companion object {
        private const val MAX_TASKS = 2_000
        private val TERMINAL = setOf("completed", "failed", "cancelled")
        private val SAFE_REF = Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$")
    }
}

/** Authenticated HTTPS transport to a LangGraph deployment; no credentials are persisted in task envelopes. */
internal class OrionLangGraphTransport(
    private val endpoint: String,
    private val bearerToken: () -> String?,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 30_000
) {
    fun submit(task: OrionTaskEnvelope): JSONObject {
        require(task.validate().isEmpty()) { "invalid_task_envelope" }
        val uri = URL(endpoint)
        require(uri.protocol.equals("https", true) && uri.host.isNotBlank()) { "langgraph_requires_https" }
        val token = bearerToken()?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("langgraph_authentication_missing")
        val connection = (uri.openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs.coerceIn(1_000, 30_000)
            connection.readTimeout = readTimeoutMs.coerceIn(1_000, 60_000)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Idempotency-Key", task.idempotencyKey)
            connection.setRequestProperty("traceparent", "00-${task.traceId}-${task.spanId}-01")
            connection.outputStream.use { it.write(JSONObject().put("task", task.toJson()).toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }?.take(64_000).orEmpty()
            if (code !in 200..299) throw IllegalStateException("langgraph_http_$code")
            return JSONObject(body)
        } finally { connection.disconnect() }
    }
}

/** Specialist roles share one worker contract and must supply evidence references on success. */
internal enum class OrionSpecialist { RESEARCH, CODING, DIAGNOSTICS, TRADING_ANALYSIS, VERIFICATION }
internal fun interface OrionSpecialistHandler { fun run(task: OrionTaskEnvelope): OrionWorkerResult }
internal class OrionSpecialistWorker(
    override val workerId: String,
    private val specialist: OrionSpecialist,
    private val handler: OrionSpecialistHandler
) : OrionTaskWorker {
    override val supportedKinds: Set<OrionTaskEnvelope.Kind> = when (specialist) {
        OrionSpecialist.RESEARCH -> setOf(OrionTaskEnvelope.Kind.RESEARCH, OrionTaskEnvelope.Kind.USER_GOAL, OrionTaskEnvelope.Kind.DELEGATED_STEP)
        OrionSpecialist.CODING -> setOf(OrionTaskEnvelope.Kind.CODE, OrionTaskEnvelope.Kind.DELEGATED_STEP)
        OrionSpecialist.DIAGNOSTICS -> setOf(OrionTaskEnvelope.Kind.DIAGNOSTICS, OrionTaskEnvelope.Kind.DELEGATED_STEP)
        OrionSpecialist.TRADING_ANALYSIS -> setOf(OrionTaskEnvelope.Kind.TRADING_ANALYSIS, OrionTaskEnvelope.Kind.DELEGATED_STEP)
        OrionSpecialist.VERIFICATION -> setOf(OrionTaskEnvelope.Kind.VERIFICATION, OrionTaskEnvelope.Kind.DELEGATED_STEP)
    }
    override val supportedCapabilities: Set<String> = setOf("research", "coding", "diagnostics", "trading_analysis", "verification")
    override fun execute(task: OrionTaskEnvelope): OrionWorkerResult {
        if (task.validate().isNotEmpty()) return OrionWorkerResult.Failure("invalid_task", false)
        if (specialist.name.lowercase() !in task.requestedCapabilities && task.requestedCapabilities.isNotEmpty()) {
            return OrionWorkerResult.Failure("specialist_not_requested", false)
        }
        return handler.run(task)
    }
}
