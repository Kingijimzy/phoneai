package com.phoneai.app

/** Restricts user-requested save folders to one harmless child of public Downloads. */
internal object DownloadsFolderPolicy {
    private val allowed = Regex("^[A-Za-z0-9_-]{1,60}$")

    fun normalize(requested: String?): String? {
        val value = requested?.trim().orEmpty().ifBlank { "Orion" }
        return value.takeIf { allowed.matches(it) && it != "." && it != ".." }
    }
}
