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

    /**
     * He must not invent a live device number either — a battery percentage stated as fact
     * without having actually checked it. Caught separately from the verb claims above because
     * there's no verb here, just a figure he had no business knowing.
     *
     * The real offending message said "Ten percent battery", spelled out, not "10%" — a plain
     * digit-only regex misses exactly the case it exists for, so the number token here also
     * accepts one or two number words ("ten", "twenty five") alongside digits.
     */
    private val numberWord = """(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thir(?:teen|ty)|four(?:teen|ty)|fif(?:teen|ty)|six(?:teen|ty)|seven(?:teen|ty)|eigh(?:teen|ty)|nine(?:teen|ty)|twenty|hundred)"""
    private val numberToken = """(?:\d{1,3}|$numberWord(?:[\s-]$numberWord)?)"""
    private val batteryFigureClaim = Regex(
        """(?i)\b($numberToken)\s*(?:%|percent)\s*battery\b|\bbattery(?:'s| is)?\s*(?:at\s*)?($numberToken)\s*(?:%|percent)\b"""
    )
    private val wordToNumber: Map<String, Int> = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90, "hundred" to 100
    )

    /** "10" -> 10, "ten" -> 10, "twenty five" -> 25, "twenty-five" -> 25. */
    private fun numberTokenValue(token: String): Int? {
        token.toIntOrNull()?.let { return it }
        val parts = token.lowercase().split(Regex("[\\s-]+")).mapNotNull { wordToNumber[it] }
        return when (parts.size) {
            0 -> null
            1 -> parts[0]
            else -> parts[0] + parts[1]
        }
    }

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
        batteryFigureClaim.find(text)?.let { m ->
            val token = m.groupValues[1].ifBlank { m.groupValues[2] }
            val value = numberTokenValue(token)
            if (value != null && toolsCalled.none { named(it.lowercase(), "battery", "device_info") }) {
                return "the battery is at $value%"
            }
        }
        return null
    }

    /** True for a device-state figure (no "I ...") rather than a first-person action claim. */
    private fun isFigureClaim(claim: String) = claim.startsWith("the ")

    fun prompt(claim: String) = if (isFigureClaim(claim))
        "You said $claim, but you never checked it with a tool this turn. Call the right tool to get the real figure, or drop the claim. Do not state a number you haven't actually read."
    else
        "You said you $claim, but no tool call this turn did that. Do it now with the right tool, or say plainly that you did not. Do not claim it again without proof."

    fun annotate(text: String, toolsCalled: Collection<String>): String {
        val c = unbacked(text, toolsCalled) ?: return text
        return text.trimEnd() + (if (isFigureClaim(c))
            "\n(Check: I said $c, but never checked it this turn, so treat that figure as made up.)"
        else
            "\n(Check: I said I $c, but no tool did that this turn, so treat it as not done.)")
    }
}
