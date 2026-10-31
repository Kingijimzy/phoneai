/*
 * Adapted from Hermes Agent:
 * https://github.com/NousResearch/hermes-agent
 * Upstream file: tools/tool_output_truncate.py
 * Copyright (c) 2025 Nous Research
 *
 * Kotlin adaptation for PhoneAI. Preserves the upstream 40% head / 60% tail policy and notice
 * format. Licensed under the MIT License; see licenses/HERMES-MIT-LICENSE.txt.
 */
package com.phoneai.app

import java.util.Locale

/** Retains early diagnostics and the most recent output when a tool result exceeds its budget. */
internal object HermesToolOutputTruncation {
    private const val HEAD_RATIO = 0.4

    fun truncateHeadTail(text: String, maxChars: Int, label: String = "OUTPUT"): String {
        require(maxChars > 0) { "maxChars must be positive" }
        if (text.length <= maxChars) return text
        val head = (maxChars * HEAD_RATIO).toInt()
        val tail = maxChars - head
        val omitted = text.length - head - tail
        val notice = String.format(
            Locale.US,
            "\n\n... [%s TRUNCATED - %,d chars omitted out of %,d total] ...\n\n",
            label, omitted, text.length
        )
        return text.substring(0, head) + notice + text.substring(text.length - tail)
    }
}
