package com.phoneai.app

/** One provider-neutral, immutable snapshot shared by chat, command and live voice setup. */
internal data class OrionSessionSnapshot(
    val request: String,
    val sessionId: String,
    val capturedAtMillis: Long,
    val sections: List<String>,
    val sourceLoadDiagnostics: List<String>
) {
    fun render(tokenBudget: Int): String {
        val safeSections = sections.filter { it.isNotBlank() }
        val packed = ContextWindowBudget.pack(safeSections, tokenBudget.coerceAtLeast(256))
        val receipt = buildString {
            appendLine("CENTRAL BRAIN SESSION SNAPSHOT")
            appendLine("session_id=${sessionId.ifBlank { "unavailable" }}; captured_at_ms=$capturedAtMillis; request_chars=${request.length}.")
            appendLine("source_loads=" + sourceLoadDiagnostics.joinToString("; ").take(1800))
            appendLine("sections_loaded=${packed.includedSections}; sections_omitted=${packed.omittedSections}; sections_truncated=${packed.truncatedSections}; estimated_tokens=${packed.estimatedTokens}; budget_tokens=$tokenBudget.")
            if (packed.omittedSectionLabels.isNotEmpty()) {
                appendLine("omitted_context_sections=" + packed.omittedSectionLabels.take(12).joinToString(" | "))
            }
            if (packed.truncatedSections > 0 || packed.omittedSections > 0) {
                appendLine("Some context was clipped or omitted by the deterministic priority packer. Treat unavailable history and memory as unknown; do not infer that an absent section is empty.")
            }
        }
        return packed.text + "\n\n" + receipt
    }
}
