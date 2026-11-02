package com.phoneai.app

import org.json.JSONObject

/**
 * Safe, bounded evidence attached to activity cards. Raw tool arguments, environment variables,
 * cookies, headers, credentials and full terminal output must never enter this model.
 */
internal sealed class OrionActivityEvidence {
    data class Browser(
        val title: String,
        val url: String,
        val excerpt: String? = null
    ) : OrionActivityEvidence()

    data class Terminal(
        val sessionId: String,
        val commandLabel: String,
        val outputTail: String = "",
        val exitCode: Int? = null
    ) : OrionActivityEvidence()

    data class File(
        val displayPath: String,
        val byteCount: Long? = null,
        val operation: String
    ) : OrionActivityEvidence()

    data class CodeDiff(
        val fileLabel: String,
        val additions: Int,
        val deletions: Int,
        val summary: String
    ) : OrionActivityEvidence()

    data class AgentHandoff(
        val agentId: String,
        val parentTaskId: String?,
        val objective: String,
        val status: String
    ) : OrionActivityEvidence()

    fun toJson(): JSONObject {
        val safe = OrionEvidenceSanitizer.evidence(this)
        return when (safe) {
            is Browser -> JSONObject().put("type", "browser").put("title", safe.title)
                .put("url", safe.url).put("excerpt", safe.excerpt ?: JSONObject.NULL)
            is Terminal -> JSONObject().put("type", "terminal").put("session_id", safe.sessionId)
                .put("command_label", safe.commandLabel).put("output_tail", safe.outputTail)
                .put("exit_code", safe.exitCode ?: JSONObject.NULL)
            is File -> JSONObject().put("type", "file").put("display_path", safe.displayPath)
                .put("byte_count", safe.byteCount ?: JSONObject.NULL).put("operation", safe.operation)
            is CodeDiff -> JSONObject().put("type", "code_diff").put("file_label", safe.fileLabel)
                .put("additions", safe.additions).put("deletions", safe.deletions).put("summary", safe.summary)
            is AgentHandoff -> JSONObject().put("type", "agent_handoff").put("agent_id", safe.agentId)
                .put("parent_task_id", safe.parentTaskId ?: JSONObject.NULL).put("objective", safe.objective)
                .put("status", safe.status)
        }
    }

    companion object {
        fun fromJson(raw: String?): OrionActivityEvidence? {
            if (raw.isNullOrBlank() || raw.length > 2_000) return null
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val item = when (json.optString("type")) {
                "browser" -> Browser(json.optString("title"), json.optString("url"), json.optString("excerpt").takeIf { it.isNotBlank() && it != "null" })
                "terminal" -> Terminal(json.optString("session_id"), json.optString("command_label"),
                    json.optString("output_tail"), if (json.has("exit_code") && !json.isNull("exit_code")) json.optInt("exit_code") else null)
                "file" -> File(json.optString("display_path"),
                    if (json.has("byte_count") && !json.isNull("byte_count")) json.optLong("byte_count") else null,
                    json.optString("operation"))
                "code_diff" -> CodeDiff(json.optString("file_label"), json.optInt("additions"), json.optInt("deletions"), json.optString("summary"))
                "agent_handoff" -> AgentHandoff(json.optString("agent_id"),
                    json.optString("parent_task_id").takeIf { it.isNotBlank() && it != "null" },
                    json.optString("objective"), json.optString("status"))
                else -> null
            }
            return item?.let { OrionEvidenceSanitizer.evidence(it) }
        }
    }
}

/** Redacts common credential formats and enforces strict display bounds before persistence/rendering. */
internal object OrionEvidenceSanitizer {
    private val secretPatterns = listOf(
        Regex("(?i)(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password|passwd|secret)\\s*[:=]\\s*[^ ,;]+"),
        Regex("(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*"),
        Regex("\\bsk-[A-Za-z0-9_-]{12,}\\b")
    )

    fun text(value: String?, max: Int = 500): String {
        require(max in 1..2000)
        var safe = value.orEmpty().replace(Regex("[\\r\\n\\t]+"), " ").trim()
        secretPatterns.forEach { safe = it.replace(safe) { match ->
            val prefix = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (prefix.isBlank()) "[redacted]" else "$prefix=[redacted]"
        } }
        safe = safe.replace(Regex("(?i)(https?://[^\\s?#]+)[?#][^\\s]*"), "$1")
        return safe.take(max)
    }

    fun evidence(item: OrionActivityEvidence): OrionActivityEvidence = when (item) {
        is OrionActivityEvidence.Browser -> item.copy(
            title = text(item.title, 120), url = safeHttpUrl(item.url), excerpt = text(item.excerpt, 400).ifBlank { null }
        )
        is OrionActivityEvidence.Terminal -> item.copy(
            sessionId = text(item.sessionId, 96), commandLabel = text(item.commandLabel, 120),
            outputTail = text(item.outputTail, 400)
        )
        is OrionActivityEvidence.File -> item.copy(
            displayPath = text(item.displayPath, 240), operation = text(item.operation, 40),
            byteCount = item.byteCount?.coerceAtLeast(0L)
        )
        is OrionActivityEvidence.CodeDiff -> item.copy(
            fileLabel = text(item.fileLabel, 160), additions = item.additions.coerceAtLeast(0),
            deletions = item.deletions.coerceAtLeast(0), summary = text(item.summary, 300)
        )
        is OrionActivityEvidence.AgentHandoff -> item.copy(
            agentId = text(item.agentId, 96), parentTaskId = item.parentTaskId?.let { text(it, 96) },
            objective = text(item.objective, 300), status = text(item.status, 40)
        )
    }

    private fun safeHttpUrl(raw: String): String {
        val value = text(raw, 500)
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return ""
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return ""
        return runCatching {
            java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
        }.getOrDefault("")
    }
}
