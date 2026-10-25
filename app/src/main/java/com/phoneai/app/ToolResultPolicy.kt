package com.phoneai.app

/** Conservative classifier for explicit tool failure results; never treats a normal result as success proof. */
internal object ToolResultPolicy {
    fun failureReason(result: String): String? {
        val clean = result.trim()
        if (clean.isEmpty()) return "The tool returned an empty result."
        val prefixes = listOf("error", "failed", "failure", "denied", "blocked", "timed out", "timeout", "cancelled", "canceled")
        val match = prefixes.firstOrNull { clean.startsWith(it, ignoreCase = true) } ?: return null
        return clean.take(220).ifBlank { "Tool reported $match." }
    }
}
