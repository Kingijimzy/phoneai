package com.phoneai.app

/** Turns internal task markers into a readable one-line status for the floating strip. */
internal object OrionStripText {
    private val marker = Regex("^\\s*\\[(?:TASK:[A-Z_]+|OWNER APPROVAL REQUIRED|ORION[^\\]]*)\\]\\s*")
    private val verified = Regex("(?i)FILE SAVED AND CONTENT VERIFIED:?\\s*")

    private val shellMarker = Regex("\\[SHELL:[A-Z]+]\\s*")
    private val shellOutput = Regex("(?i)SHELL OUTPUT \\([^)]*\\):?\\s*")

    /** Removes model-only directives that ride on a tool result so they never reach the visible chat. */
    fun visible(result: String): String =
        result.substringBefore("\n[EXECUTIVE]").substringBefore("\n[LEARNING STORAGE WARNING]")

    fun clean(raw: String): String {
        var t = visible(raw).replace("\u2063", " ").replace(shellMarker, "").replace(shellOutput, "").replace(Regex("^\\s*\\$ "), "").trim()
        repeat(2) { t = t.replace(marker, "") }
        t = t.replace(verified, "Saved: ")
        t = t.replace(Regex("^([a-z_]+)\\s+[—-]\\s+", RegexOption.IGNORE_CASE)) { m -> m.groupValues[1].replace('_', ' ').replaceFirstChar { it.uppercase() } + ": " }
        t = t.replace(Regex("\\s*\\|\\s*"), " · ").replace(Regex("\\s+"), " ").trim()
        return t.take(140)
    }
}
