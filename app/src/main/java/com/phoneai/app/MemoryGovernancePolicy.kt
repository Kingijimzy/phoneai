package com.phoneai.app

/** Deterministic memory governance; generated content never becomes trusted merely by being saved. */
internal object MemoryGovernancePolicy {
    fun sensitivity(text: String, requested: String = "general"): String {
        if (requested.lowercase().trim() in setOf("sensitive", "high")) return "sensitive"
        val risky = Regex("(?i)\\b(password|passcode|private key|seed phrase|recovery phrase|api[_ -]?key|secret|otp|one[- ]time code)\\b")
        return if (risky.containsMatchIn(text)) "sensitive" else "general"
    }

    fun canUseInPrompt(reviewStatus: String): Boolean = reviewStatus == "approved"

    fun normalizeConfidence(value: Double): Double =
        if (value.isFinite()) value.coerceIn(0.0, 1.0) else 0.0
}
