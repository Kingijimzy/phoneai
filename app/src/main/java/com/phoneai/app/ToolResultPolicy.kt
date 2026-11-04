package com.phoneai.app

/** Conservative classifier for explicit tool failure results; never treats a normal result as success proof. */
internal object ToolResultPolicy {
    fun failureReason(result: String): String? {
        val clean = result.trim()
        if (clean.isEmpty()) return "The tool returned an empty result."
        val prefixes = listOf(
            "error", "failed", "failure", "denied", "blocked", "timed out", "timeout", "cancelled", "canceled",
            "not found", "not available", "not configured", "not connected", "unavailable", "unsupported",
            "invalid", "rejected", "refused", "no such", "could not", "cannot"
        )
        val match = prefixes.firstOrNull { clean.startsWith(it, ignoreCase = true) } ?: return null
        return clean.take(220).ifBlank { "Tool reported $match." }
    }
}
