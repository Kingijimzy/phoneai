package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Versioned, provider-neutral task contract shared by local and remote workers.
 *
 * This is metadata only: it never carries credentials, raw tool arguments, or permission
 * grants. Workers must resolve capabilities and policy again at execution time.
 */
internal data class OrionTaskEnvelope(
    val schemaVersion: Int,
    val taskId: String,
    val parentTaskId: String?,
    val rootTaskId: String,
    val objective: String,
    val kind: Kind,
    val requestedCapabilities: List<String>,
    val inputEvidenceRefs: List<String>,
    val ownerScope: String,
    val createdAt: Long,
    val deadlineAt: Long?,
    val attempt: Int,
    val idempotencyKey: String,
    val traceId: String,
    val spanId: String,
    val cancellationToken: String,
    val resultSchemaVersion: Int,
    val policyVersion: String
) {
    enum class Kind { USER_GOAL, DELEGATED_STEP, RESEARCH, CODE, TRADING_ANALYSIS, DIAGNOSTICS, VERIFICATION }

    fun validate(now: Long = System.currentTimeMillis()): List<String> = buildList {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) add("unsupported_schema_version")
        if (!isSafeId(taskId)) add("invalid_task_id")
        if (parentTaskId != null && !isSafeId(parentTaskId)) add("invalid_parent_task_id")
        if (!isSafeId(rootTaskId)) add("invalid_root_task_id")
        if (objective.isBlank() || objective.length > MAX_OBJECTIVE) add("invalid_objective")
        if (requestedCapabilities.size > MAX_CAPABILITIES ||
            requestedCapabilities.any { !isSafeLabel(it, MAX_LABEL) }) add("invalid_capabilities")
        if (inputEvidenceRefs.size > MAX_EVIDENCE ||
            inputEvidenceRefs.any { !isSafeId(it) }) add("invalid_evidence_refs")
        if (!isSafeLabel(ownerScope, MAX_LABEL)) add("invalid_owner_scope")
        if (createdAt <= 0L || createdAt > now + MAX_CLOCK_SKEW_MS) add("invalid_created_at")
        if (deadlineAt != null && (deadlineAt <= createdAt || deadlineAt <= now)) add("expired_or_invalid_deadline")
        if (attempt !in 0..MAX_ATTEMPTS) add("invalid_attempt")
        if (!isSafeId(idempotencyKey)) add("invalid_idempotency_key")
        if (!isHex(traceId, 32) || !isHex(spanId, 16)) add("invalid_trace_context")
        if (!isSafeId(cancellationToken)) add("invalid_cancellation_token")
        if (resultSchemaVersion !in 1..MAX_SCHEMA_VERSION) add("invalid_result_schema")
        if (!isSafeLabel(policyVersion, MAX_LABEL)) add("invalid_policy_version")
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("taskId", taskId)
        .put("parentTaskId", parentTaskId ?: JSONObject.NULL)
        .put("rootTaskId", rootTaskId)
        .put("objective", objective)
        .put("kind", kind.name)
        .put("requestedCapabilities", JSONArray(requestedCapabilities))
        .put("inputEvidenceRefs", JSONArray(inputEvidenceRefs))
        .put("ownerScope", ownerScope)
        .put("createdAt", createdAt)
        .put("deadlineAt", deadlineAt ?: JSONObject.NULL)
        .put("attempt", attempt)
        .put("idempotencyKey", idempotencyKey)
        .put("traceId", traceId)
        .put("spanId", spanId)
        .put("cancellationToken", cancellationToken)
        .put("resultSchemaVersion", resultSchemaVersion)
        .put("policyVersion", policyVersion)

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_ATTEMPTS = 5
        private const val MAX_OBJECTIVE = 2_000
        private const val MAX_LABEL = 100
        private const val MAX_CAPABILITIES = 24
        private const val MAX_EVIDENCE = 64
        private const val MAX_SCHEMA_VERSION = 32
        private const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L
        private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        private val LABEL = Regex("^[A-Za-z0-9][A-Za-z0-9._:/ -]{0,99}$")
        private val HEX = Regex("^[a-fA-F0-9]+$")

        private fun isSafeId(value: String) = ID.matches(value)
        private fun isSafeLabel(value: String, max: Int) = value.length <= max && LABEL.matches(value)
        private fun isHex(value: String, length: Int) = value.length == length && HEX.matches(value)

        fun create(
            objective: String,
            kind: Kind = Kind.USER_GOAL,
            ownerScope: String = "device_owner",
            capabilities: List<String> = emptyList(),
            evidenceRefs: List<String> = emptyList(),
            parentTaskId: String? = null,
            rootTaskId: String? = null,
            deadlineAt: Long? = null,
            policyVersion: String = "1",
            now: Long = System.currentTimeMillis()
        ): OrionTaskEnvelope {
            val id = UUID.randomUUID().toString()
            val trace = UUID.randomUUID().toString().replace("-", "")
            val span = UUID.randomUUID().toString().replace("-", "").take(16)
            return OrionTaskEnvelope(
                CURRENT_SCHEMA_VERSION, id, parentTaskId, rootTaskId ?: parentTaskId ?: id,
                objective.trim().take(MAX_OBJECTIVE), kind,
                capabilities.distinct().take(MAX_CAPABILITIES),
                evidenceRefs.distinct().take(MAX_EVIDENCE), ownerScope, now, deadlineAt, 0,
                UUID.randomUUID().toString(), trace, span, UUID.randomUUID().toString(),
                1, policyVersion
            )
        }

        fun fromJson(json: JSONObject): OrionTaskEnvelope {
            fun strings(key: String): List<String> {
                val array = json.optJSONArray(key) ?: JSONArray()
                return (0 until array.length()).map { array.optString(it) }
            }
            val parent = json.opt("parentTaskId")?.takeUnless { it == JSONObject.NULL }?.toString()
            val deadline = json.opt("deadlineAt")?.takeUnless { it == JSONObject.NULL }?.toString()?.toLongOrNull()
            return OrionTaskEnvelope(
                json.optInt("schemaVersion", -1), json.optString("taskId"),
                parent, json.optString("rootTaskId"), json.optString("objective"),
                runCatching { Kind.valueOf(json.optString("kind")) }.getOrElse {
                    throw IllegalArgumentException("unknown_task_kind")
                },
                strings("requestedCapabilities"), strings("inputEvidenceRefs"),
                json.optString("ownerScope"), json.optLong("createdAt", -1L), deadline,
                json.optInt("attempt", -1), json.optString("idempotencyKey"),
                json.optString("traceId"), json.optString("spanId"),
                json.optString("cancellationToken"), json.optInt("resultSchemaVersion", -1),
                json.optString("policyVersion")
            )
        }
    }
}
