package com.phoneai.app

/**
 * He must not say he did something no tool did. A first-person claim ("I've pushed it", "I sent the message")
 * is checked against the tools that actually ran this turn; an unbacked claim gets one chance to do the thing or
 * retract it, and whatever is left is flagged in the reply. This is what stops him contradicting himself.
 */
internal object OrionClaimCheck {
    private val claim = Regex(
        """(?i)\bI(?:'ve| have)?\s+(?:just\s+|now\s+|already\s+)?(pushed|merged|committed|sent|emailed|messaged|texted|saved|wrote|created|installed|downloaded|deleted|removed|opened|launched|started|set|scheduled)\b([^.\n!?]{0,60})"""
    )
    private val fileish = Regex("""(?i)(file|script|folder|project|page|\.\w{2,4}\b|workspace|note)""")

    private fun named(tool: String, vararg parts: String) = parts.any { tool.contains(it) }
    private val shellish = setOf("shell", "devspace", "dev_shell", "termux_command", "python_exec", "dev_python")

    /** Which of the tools that ran could back this claim. */
    private fun backed(verb: String, rest: String, tool: String): Boolean {
        val t = tool.lowercase()
        return when (verb.lowercase()) {
            "pushed", "merged", "committed" -> t in shellish || named(t, "github", "git_")
            "sent", "emailed", "messaged", "texted" -> named(t, "send", "gmail", "telegram", "sms", "whatsapp", "mail", "message", "a2a", "mesh")
            "saved", "wrote", "created" -> t in shellish || named(t, "write", "save", "create", "forge", "extension", "brain", "remember", "task", "calendar", "event", "show_file", "fact")
            "installed", "downloaded" -> t in shellish || named(t, "download", "install")
            "deleted", "removed" -> t in shellish || named(t, "delete", "remove", "forget")
            "opened", "launched", "started" -> t in shellish || named(t, "open", "launch", "play", "browser", "media", "preview", "studio", "show_file", "start")
            "set", "scheduled" -> named(t, "alarm", "timer", "remind", "schedule", "task", "routine", "calendar", "quiet", "job")
            else -> true
        }
    }

    private fun applies(verb: String, rest: String): Boolean = when (verb.lowercase()) {
        "saved", "wrote", "created" -> fileish.containsMatchIn(rest)
        "sent", "emailed", "messaged", "texted" -> true
        "deleted", "removed" -> fileish.containsMatchIn(rest) || Regex("(?i)(task|memory|reminder)").containsMatchIn(rest)
        "opened", "launched", "started" -> Regex("(?i)(app|page|site|browser|link|http|settings|youtube|spotify|music|game)").containsMatchIn(rest)
        "set", "scheduled" -> Regex("(?i)(alarm|timer|reminder|schedule)").containsMatchIn(rest)
        else -> true
    }

    /** The first unbacked claim, as "pushed it", or null when everything he says he did was done. */
    fun unbacked(text: String, toolsCalled: Collection<String>): String? {
        if (text.isBlank()) return null
        claim.findAll(text).forEach { m ->
            val verb = m.groupValues[1]; val rest = m.groupValues[2]
            if (applies(verb, rest) && toolsCalled.none { backed(verb, rest, it) }) return (verb + rest).trim().take(60)
        }
        return null
    }

    fun prompt(claim: String) =
        "You said you $claim, but no tool call this turn did that. Do it now with the right tool, or say plainly that you did not. Do not claim it again without proof."

    fun annotate(text: String, toolsCalled: Collection<String>): String {
        val c = unbacked(text, toolsCalled) ?: return text
        return text.trimEnd() + "\n(Check: I said I $c, but no tool did that this turn, so treat it as not done.)"
    }
}
