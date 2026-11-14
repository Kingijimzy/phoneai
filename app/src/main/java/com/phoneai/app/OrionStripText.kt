package com.phoneai.app

/** Turns internal task markers into a readable one-line status for the floating strip. */
internal object OrionStripText {
    private val marker = Regex("^\\s*\\[(?:TASK:[A-Z_]+|OWNER APPROVAL REQUIRED|ORION[^\\]]*)\\]\\s*")
    private val verified = Regex("(?i)FILE SAVED AND CONTENT VERIFIED:?\\s*")

    fun clean(raw: String): String {
        var t = raw.trim()
        repeat(2) { t = t.replace(marker, "") }
        t = t.replace(verified, "Saved: ")
        t = t.replace(Regex("^([a-z_]+)\\s+[—-]\\s+", RegexOption.IGNORE_CASE)) { m -> m.groupValues[1].replace('_', ' ').replaceFirstChar { it.uppercase() } + ": " }
        t = t.replace(Regex("\\s*\\|\\s*"), " · ").replace(Regex("\\s+"), " ").trim()
        return t.take(140)
    }
}
