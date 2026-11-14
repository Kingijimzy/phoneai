package com.phoneai.app

/** Presentation-only summary for a unified diff. It never applies or mutates a workspace patch. */
data class OrionCodeDiffStats(
    val isDiff: Boolean,
    val additions: Int,
    val removals: Int
)

object OrionCodeDiffPresentation {
    fun inspect(language: String, source: String): OrionCodeDiffStats {
        val lines = source.lineSequence().toList()
        val hasHunks = lines.any { it.startsWith("@@ ") }
        val hasBothSigns = lines.any { it.startsWith("+") && !it.startsWith("+++") } &&
            lines.any { it.startsWith("-") && !it.startsWith("---") }
        val isDiff = language.equals("diff", true) || language.equals("patch", true) ||
            hasHunks || hasBothSigns
        return OrionCodeDiffStats(
            isDiff = isDiff,
            additions = lines.count { it.startsWith("+") && !it.startsWith("+++") },
            removals = lines.count { it.startsWith("-") && !it.startsWith("---") }
        )
    }
}
