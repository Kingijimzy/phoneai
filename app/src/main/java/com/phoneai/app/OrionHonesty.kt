package com.phoneai.app

/**
 * Models sometimes write "dev_shell executed `pip install x` ... returned Python 3.11" without having called
 * the tool. Those sentences are invented results. They are removed from the visible reply and replaced by a
 * plain correction, so Kingi never relies on a command that never ran.
 */
internal object OrionHonesty {
    private val claim = Regex(
        "(?i)\\b(dev_shell|dev_write|dev_read|dev_python|python_exec|workspace_write|workspace_read|find_files|fetch_url|research_web|live_web_search|termux_command|shell)\\b\\s*(?:`[^`]*`\\s*)?(?:executed|ran|returned|reported|confirmed|printed|failed|wrote|saved)"
    )

    fun scrub(say: String, toolsActuallyCalled: Set<String>): String {
        if (say.isBlank() || !claim.containsMatchIn(say)) return say
        val parts = say.split(Regex("(?<=[.!?])\\s+|\\n+"))
        var dropped = 0
        val kept = parts.filter { sentence ->
            val m = claim.find(sentence)
            val invented = m != null && m.groupValues[1].lowercase() !in toolsActuallyCalled
            if (invented) dropped++
            !invented
        }
        if (dropped == 0) return say
        val body = kept.joinToString("\n").trim()
        val note = "(Correction: I described $dropped tool result${if (dropped > 1) "s" else ""} I had not actually run. Ignore that part; I'll run it properly.)"
        return if (body.isBlank()) note else "$body\n$note"
    }
}
