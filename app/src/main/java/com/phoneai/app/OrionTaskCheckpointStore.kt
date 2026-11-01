package com.phoneai.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Per-task crash checkpoints. Each delegated task owns an independent AtomicFile, so
 * concurrent workers cannot overwrite one another's in-flight marker or recovery history.
 */
internal class OrionTaskCheckpointStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "orion_task_checkpoints").apply { mkdirs() }
    private fun file(taskId: String): File {
        require(taskId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$"))) { "invalid_task_id" }
        val digest = MessageDigest.getInstance("SHA-256").digest(taskId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(root, "$digest.json")
    }
    private fun atomic(taskId: String) = AtomicFile(file(taskId))
    private fun read(taskId: String): JSONObject? = runCatching {
        atomic(taskId).openRead().bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()
    private fun write(taskId: String, value: JSONObject): Boolean {
        val target = atomic(taskId)
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = target.startWrite()
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
            true
        } catch (_: Exception) {
            stream?.let { runCatching { target.failWrite(it) } }
            false
        }
    }

    @Synchronized fun begin(taskId: String, objective: String): Boolean {
        if (objective.isBlank() || objective.length > 2_000) return false
        val old = read(taskId)
        if (old?.optString("status") in setOf("running", "paused", "recovery_required")) return false
        val now = System.currentTimeMillis()
        return write(taskId, JSONObject().put("taskId", taskId).put("objective", objective)
            .put("status", "running").put("startedAt", now).put("updatedAt", now)
            .put("steps", JSONArray()))
    }

    @Synchronized fun pending(taskId: String): JSONObject? = read(taskId)?.takeIf {
        it.optString("status") in setOf("running", "paused", "recovery_required")
    }

    @Synchronized fun beginAction(taskId: String, action: String, inputDigest: String): Boolean {
        if (action.isBlank() || action.length > 100 || !inputDigest.matches(Regex("^[a-f0-9]{64}$"))) return false
        val state = read(taskId) ?: return false
        if (state.optString("status") !in setOf("running", "paused") || state.optJSONObject("inFlight") != null) return false
        state.put("inFlight", JSONObject().put("action", action).put("inputDigest", inputDigest)
            .put("startedAt", System.currentTimeMillis())).put("status", "running")
        state.put("updatedAt", System.currentTimeMillis())
        return write(taskId, state)
    }

    @Synchronized fun recordAction(taskId: String, action: String, outcome: String): Boolean {
        val state = read(taskId) ?: return false
        val active = state.optJSONObject("inFlight") ?: return false
        if (active.optString("action") != action) return false
        val steps = state.optJSONArray("steps") ?: JSONArray()
        steps.put(JSONObject().put("action", action.take(100)).put("outcome", outcome.take(500))
            .put("at", System.currentTimeMillis()))
        while (steps.length() > 32) steps.remove(0)
        state.put("steps", steps).remove("inFlight").put("updatedAt", System.currentTimeMillis())
        return write(taskId, state)
    }

    @Synchronized fun needsOwner(taskId: String): Boolean = read(taskId)?.let { it.optJSONObject("inFlight") != null || it.optString("status") == "recovery_required" } ?: false

    @Synchronized fun requireRecovery(taskId: String, reason: String): Boolean {
        val state = read(taskId) ?: return false
        state.put("status", "recovery_required").put("recoveryNotice", reason.take(500))
            .put("updatedAt", System.currentTimeMillis())
        return write(taskId, state)
    }

    @Synchronized fun acknowledge(taskId: String, ownerConfirmation: String): Boolean {
        if (ownerConfirmation.isBlank()) return false
        val state = read(taskId) ?: return false
        if (state.optJSONObject("inFlight") == null && state.optString("status") != "recovery_required") return false
        state.remove("inFlight").put("status", "paused")
            .put("ownerConfirmedAt", System.currentTimeMillis())
            .put("ownerConfirmation", ownerConfirmation.take(240))
            .put("updatedAt", System.currentTimeMillis())
        return write(taskId, state)
    }

    @Synchronized fun resumeInstruction(taskId: String): String? {
        val state = pending(taskId) ?: return null
        val steps = state.optJSONArray("steps") ?: JSONArray()
        val summary = (0 until steps.length()).mapNotNull { steps.optJSONObject(it) }
            .joinToString("\\n") { "- ${it.optString("action")}: ${it.optString("outcome")}" }
        val active = state.optJSONObject("inFlight")
        if (active != null) return "RECOVERY REQUIRED: action '${active.optString("action")}' has unknown outcome. Inspect state; never replay until owner reconciliation."
        return "Resume delegated task ${taskId}. Recorded steps:\\n$summary"
    }

    @Synchronized fun complete(taskId: String): Boolean {
        val state = read(taskId) ?: return false
        if (state.optJSONObject("inFlight") != null) return false
        state.put("status", "completed").put("updatedAt", System.currentTimeMillis())
        return write(taskId, state)
    }
}
