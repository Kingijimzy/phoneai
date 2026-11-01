package com.phoneai.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Durable LangGraph receipt ledger. A task is submitted once; restarts resume polling its receipt. */
internal class OrionRemoteTaskReceiptStore(context: Context) {
    data class Receipt(val taskId: String, val idempotencyKey: String, val statusUrl: String,
                       val cancelUrl: String?, val terminalStatus: String?, val evidenceRef: String?)
    private val lock = Any()
    private val atomic = AtomicFile(File(context.applicationContext.filesDir, "orion_remote_receipts.json"))

    fun get(task: OrionTaskEnvelope): Receipt? = synchronized(lock) {
        val row = read().optJSONObject("receipts")?.optJSONObject(task.taskId) ?: return@synchronized null
        if (row.optString("idempotencyKey") != task.idempotencyKey) return@synchronized null
        Receipt(task.taskId, task.idempotencyKey, row.optString("statusUrl"),
            row.optString("cancelUrl").takeIf { it.isNotBlank() && it != "null" },
            row.optString("terminalStatus").takeIf { it.isNotBlank() && it != "null" },
            row.optString("evidenceRef").takeIf { it.isNotBlank() && it != "null" })
    }

    fun saveAccepted(task: OrionTaskEnvelope, statusUrl: String, cancelUrl: String?): Boolean = synchronized(lock) {
        val root = read(); val rows = root.optJSONObject("receipts") ?: JSONObject()
        val existing = rows.optJSONObject(task.taskId)
        if (existing != null && existing.optString("idempotencyKey") != task.idempotencyKey) return@synchronized false
        rows.put(task.taskId, JSONObject().put("idempotencyKey", task.idempotencyKey)
            .put("statusUrl", statusUrl).put("cancelUrl", cancelUrl ?: "")
            .put("terminalStatus", "").put("evidenceRef", ""))
        root.put("receipts", rows); write(root)
    }

    fun saveTerminal(task: OrionTaskEnvelope, status: String, evidenceRef: String?): Boolean = synchronized(lock) {
        val root = read(); val rows = root.optJSONObject("receipts") ?: return@synchronized false
        val row = rows.optJSONObject(task.taskId) ?: return@synchronized false
        if (row.optString("idempotencyKey") != task.idempotencyKey) return@synchronized false
        row.put("terminalStatus", status).put("evidenceRef", evidenceRef ?: "")
        rows.put(task.taskId, row); root.put("receipts", rows); write(root)
    }

    private fun read() = runCatching { JSONObject(atomic.openRead().bufferedReader().use { it.readText() }) }.getOrElse { JSONObject() }
    private fun write(root: JSONObject): Boolean {
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = atomic.startWrite(); stream.write(root.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream); true
        } catch (_: Exception) { stream?.let { runCatching { atomic.failWrite(it) } }; false }
    }
}
