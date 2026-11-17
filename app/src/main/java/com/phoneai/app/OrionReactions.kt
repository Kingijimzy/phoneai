package com.phoneai.app

/** Instant, model-free reactions for pure laughter or acknowledgement, so Orion answers a laugh at once. */
internal object OrionReactions {
    private val laugh = Regex("^(?:ha|he|hi|hee|lol|lmao|haha+|hehe+|ahah+)[\\s!.,ha]*$", RegexOption.IGNORE_CASE)
    private val laughWords = setOf("lol", "lmao", "rofl", "haha", "hahaha", "hehe", "ahaha")

    fun isPureLaughter(text: String): Boolean {
        val t = text.trim().lowercase().trimEnd('!', '.', ' ')
        if (t.isEmpty() || t.length > 24) return false
        return t in laughWords || (t.startsWith("ha") && laugh.matches(t)) || t.replace(" ", "").matches(Regex("(ha)+h?|(he)+h?"))
    }

    /** Returns a short spoken reaction, or null when the owner said something that needs a real answer. */
    fun forUtterance(text: String, salt: Int = 0): String? {
        if (!isPureLaughter(text)) return null
        val options = listOf("Ha! Go on.", "Ha ha. I heard that.", "Mm, glad that landed.")
        return options[Math.floorMod(salt, options.size)]
    }
}
