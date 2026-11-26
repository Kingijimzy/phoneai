package com.phoneai.app

/**
 * OrionPersona already says "never ship a stock phrase" — but a sentence in a prompt is not
 * consistent behaviour, as the owner found directly: a conversational turn (no tool call behind
 * it) whose entire visible reply was nothing but "On it.", "Doing it." or "One second." — a bare
 * acknowledgment standing in as the whole answer, with no information in it at all. OrionVoice's
 * stock-phrase filter only ever covered the *opener* shown before a tool call; it never ran on a
 * turn with no tool call, so a bare acknowledgment used as the entire reply sailed straight
 * through. This closes that gap the same way Cognitive Pushback and Claim Check already do: a
 * deterministic check in the execution loop, not another line of prompt text to hope is followed.
 */
internal object OrionBareAcknowledgmentCheck {
    private val stock = Regex(
        """(?i)^(on it|working on it|let me think|thinking|sure|okay|ok|got it|""" +
        """one moment|just a moment|one second|one sec|doing it|""" +
        """i'?m (on|working through) (it|that)( now)?)[.!…]*$"""
    )

    /** True only for a reply that is nothing but a stock acknowledgment: short, no real sentence behind it. */
    fun isBareAcknowledgment(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 40) return false
        return stock.matches(trimmed)
    }

    const val PROMPT = "That reply was just a stock acknowledgment (\"on it\" / \"doing it\" / " +
        "\"one second\"-style) with nothing behind it — no tool call, no real content, nothing " +
        "the owner can read and actually learn from. Either say the specific, true thing you are " +
        "about to do or just found, in one concrete line, or give the real answer. Do not repeat a " +
        "placeholder phrase."
}
