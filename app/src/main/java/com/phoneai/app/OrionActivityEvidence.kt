package com.phoneai.app

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
        return value
    }
}
