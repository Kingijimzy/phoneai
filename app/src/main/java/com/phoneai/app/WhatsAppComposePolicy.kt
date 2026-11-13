package com.phoneai.app

/** Validates the handoff payload; it never sends a message. */
internal object WhatsAppComposePolicy {
    fun normalizePhone(raw: String): String? {
        val value = raw.trim()
        if (!value.matches(Regex("^\\+?[1-9][0-9]{7,14}$"))) return null
        return value.removePrefix("+")
    }

    fun validateMessage(raw: String): String? {
        val value = raw.trim()
        return value.takeIf { it.isNotBlank() && it.length <= 1000 }
    }
}