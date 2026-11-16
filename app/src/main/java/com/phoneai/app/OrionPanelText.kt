package com.phoneai.app

/** Pure text helpers for the command-center conversation panel: nothing code-like or raw-markdown leaks into it. */
internal object OrionPanelText {
    private val fence = Regex("```[\\s\\S]*?(```|$)")

    /** Reply text for the panel: code blocks collapse to a tappable line, markdown marks are dropped. */
    fun clean(text: String): String {
        if (text.isBlank()) return ""
        var t = fence.replace(text) { "\n▣ Code — open the card below\n" }
        t = t.replace("**", "").replace("__", "").replace("`", "")
        t = t.lines().joinToString("\n") { it.trimStart('#', ' ').let { l -> if (l.startsWith("- ") || l.startsWith("* ")) "• " + l.drop(2) else l } }
        return t.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun userLine(text: String): String = "You: " + text.replace('\n', ' ').trim()

    /** The one-line state shown in the status pill. */
    fun statusLine(mode: OrbView.Mode, busy: Boolean, runningTitle: String?): String = when {
        mode == OrbView.Mode.SPEAKING -> "Speaking. Talk to interrupt"
        mode == OrbView.Mode.LISTENING -> "Listening…"
        mode == OrbView.Mode.WAITING_APPROVAL -> "Waiting for your approval"
        mode == OrbView.Mode.ERROR -> "Something failed — tap to retry by voice"
        busy && !runningTitle.isNullOrBlank() -> "$runningTitle…"
        busy || mode == OrbView.Mode.THINKING -> "Thinking…"
        mode == OrbView.Mode.ACTING -> "Working…"
        else -> "Tap the orb to talk"
    }

    fun stepsLabel(done: Int, total: Int): String = if (total == 0) "" else "$done of $total step${if (total == 1) "" else "s"}"

    fun chip(done: Int, total: Int, failed: Int, running: Boolean): String = when {
        running -> "RUNNING"
        failed > 0 && failed == total -> "FAILED"
        failed > 0 -> "PARTIAL"
        total > 0 -> "COMPLETED"
        else -> ""
    }
}
