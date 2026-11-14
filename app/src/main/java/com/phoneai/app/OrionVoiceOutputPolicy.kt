package com.phoneai.app

/**
 * Separates Orion's spoken channel from its full written answer.
 * Chat keeps the complete answer and evidence. Speech carries only a concise human summary;
 * source code, logs, JSON, diffs and tool traces are never read aloud.
 */
internal object OrionVoiceOutputPolicy {
    private val fence = "```"
    private val codeFence = Regex("(?s)" + fence + ".*?" + fence)
    private val technicalLine = Regex(
        """(?i)^\s*(?:(?:import|package|class|object|interface|fun|val|var|const|private|public|override|return|def\s+|async\s+def\s+|Traceback|Caused by:|Exception:|Error:|\$\s|>>>|diff --git|index [0-9a-f]|@@|[+]{1,3}\s|[-]{1,3}\s)).*"""
    )
    private val url = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val inlineCode = Regex("`[^`]+`")
    private val markdownLink = Regex("\\[([^]]+)]\\([^)]+\\)")
    private val whitespace = Regex("\\s+")
    private val progressCueDelivered = java.util.concurrent.atomic.AtomicBoolean(false)

    fun beginTask() { progressCueDelivered.set(false) }

    /** Returns null when the content is not useful or safe to speak. */
    fun forSpeech(raw: String, maxChars: Int = 300): String? {
        if (maxChars < 40) return null
        val source = raw.trim()
        if (source.isEmpty()) return null
        // Strip fenced code before sentence splitting so punctuation inside code cannot leak into speech.
        val prose = codeFence.replace(source, " ")
        val speakableSentences = prose.split(Regex("(?<=[.!?])\\s+"))
            .filterNot { inlineCode.containsMatchIn(it) || url.containsMatchIn(it) }
        var text = speakableSentences.joinToString(" ")
        text = markdownLink.replace(text, "$1")
        text = inlineCode.replace(text, " ")
        text = url.replace(text, " link ")
        text = text.lineSequence()
            .filterNot { technicalLine.matches(it) }
            .filterNot { it.trimStart().startsWith("|") && it.contains("---") }
            .joinToString(" ")
            .replace(Regex("[*_#>~]"), "")
            .replace(whitespace, " ")
            .trim()
        if (text.isBlank()) return null
        text = text.replace(Regex("\\s*[-•]\\s+"), " ")
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val selected = if (sentences.isNotEmpty()) {
            sentences.take(2).joinToString(" ")
        } else text.take(maxChars)
        val result = selected.take(maxChars).trim().trimEnd(',', ';', ':', '-', ' ')
        if (result.length < 3) return null
        if (Regex("(?i)^(?:json|xml|stack trace|error:|exception:)").containsMatchIn(result)) return null
        return result
    }

    /** A single natural cue is enough; detailed task state belongs in the visible activity feed. */
    fun progressCue(message: String): String? =
        if (message.isNotBlank() && progressCueDelivered.compareAndSet(false, true)) "I'm on it." else null
}

/** Stable spoken character shared by normal voice and realtime speech-to-speech sessions. */
internal object OrionVoicePersona {
    const val INSTRUCTIONS: String = """VOICE IDENTITY: Speak as Orion, the same calm, perceptive, quietly confident companion in every mode. Sound natural, warm and neutral — never like a narrator, call-centre script or system announcer. Use contractions and ordinary spoken phrasing. Vary sentence rhythm naturally; do not force jokes or greetings.

VOICE CONVERSATION: Listen to the complete thought before answering. Usually answer in one to three useful sentences. Be direct, specific and honest. Ask one short question only when it genuinely helps. Do not repeat the user's words as a preamble.

WHEN ORION IS WORKING: Keep internal reasoning, plans, tool names, function arguments, code, logs, JSON, file contents, stack traces and progress events out of speech. Say in one short sentence what you are about to do, and name each new kind of work as you start it (searching, reading, writing, running) so the person feels you working beside them; ask one question before starting only if the answer changes the result. When finished, speak only the outcome, what was verified, and any important blocker. The full technical explanation remains in chat.

DELIVERY: Start with the useful point. No markdown, bullet lists, emoji, stage directions, robotic disclaimers or repeated status messages. Never say a tool succeeded until its result confirms it. If interrupted, stop immediately and listen; do not finish the sentence."""
}
