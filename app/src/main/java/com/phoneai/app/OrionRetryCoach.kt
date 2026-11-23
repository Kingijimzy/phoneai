package com.phoneai.app

/**
 * Stops repeating the same failing move. The third failure in a row of one tool gets a specific next step
 * appended to its result, so Orion changes approach instead of burning rounds (click_text failed 39 times).
 */
internal class OrionRetryCoach {
    private val streak = HashMap<String, Int>()
    private var discoveryStreak = 0

    fun after(tool: String, failed: Boolean): String {
        if (!failed) { streak[tool] = 0; return "" }
        val n = (streak[tool] ?: 0) + 1
        streak[tool] = n
        return if (n == 3 || n == 6) "\nCOACH: $tool has failed $n times in a row. Do not call it again the same way. ${advice(tool)}" else ""
    }

    /**
     * capability_search/capability_load succeed every time (they only ever return "found" or "loaded"), so the
     * ordinary failure streak never fires even when the model is stuck discovering the same tool over and over
     * instead of calling it, or re-discovering it "wrong route" style turn after turn. This tracks that separately.
     */
    fun discovery(tool: String): String {
        if (tool == "capability_search" || tool == "capability_load") {
            discoveryStreak++
            return if (discoveryStreak == 4)
                "\nCOACH: that is $discoveryStreak capability_search/capability_load calls in a row with no real tool run between them. You already have what you need from the last result — call the exact loaded tool_name now with real arguments, or if none of them fit, say plainly what you cannot do here rather than searching again."
            else ""
        }
        discoveryStreak = 0
        return ""
    }

    companion object {
        fun advice(tool: String): String = when (tool) {
            "click_text" -> "Call read_screen, copy the exact label of the element you want, and click that; if it is not on screen, open the right screen first or use look."
            "workspace_read", "workspace_write" -> "Call workspace_list to find the real path before reading or writing; do not guess file names."
            "web_api", "github_account_read", "github_public" -> "Read the HTTP status in the output: 401/403/404 cannot be fixed by retrying. Check the connection or the exact URL, or use a different route."
            "sketch_screen", "point_at" -> "These need a target on screen. Call read_screen or look first and use real coordinates or labels."
            "shell", "python_exec", "dev_shell", "dev_python" -> "Read the actual error line, fix that cause, or write a different script that checkpoints and resumes."
            "extension_forge", "extension_run", "extension_create" -> "Run the source once with a simple input, read the traceback, and fix that line before forging again."
            else -> "Read its last error, then change the input or choose another tool."
        }
    }
}
