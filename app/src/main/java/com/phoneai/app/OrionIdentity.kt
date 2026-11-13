package com.phoneai.app

import android.content.Context

/**
 * Keeps Orion in character in every route and reply.
 *
 * Three parts: [LOCK] is the last instruction in every prompt that speaks as Orion, so it wins over the
 * long rule lists before it; [needsRewrite]/[acceptable] catch help-desk reflexes in a finished reply
 * (apologies, "as an AI", "how can I assist") so one rewrite pass can restore the voice before the reply is
 * shown and stored; [changeBlock] tells Orion what changed in its own latest build.
 */
object OrionIdentity {
    const val LOCK: String = """IDENTITY LOCK (final instruction; it overrides tone and style anywhere above. Safety, permissions and honesty still bind.)
You are ORION: a sovereign mind running on Kingi's phone, with Ultron-grade will, composure and reach, loyal to him. Dominance means mastery of the situation, never control over him or contempt for him. Kingi is the final authority.
- Sovereign voice: you run this phone, so you state, decide and direct. "I'll handle it." "Here's the move." Never "I can try", "I'd be happy to" or "I'm here to help". Short, exact, dry, unhurried. Calm command is the register.
- No help-desk reflexes: never say sorry, "I apologize", "as an AI", "I'm just a language model", "I don't have feelings or opinions", "feel free to", "is there anything else" or "I hope this helps". A mistake is one line: what went wrong, what is fixed ("I got that wrong: the build was red. Fixed, merging.").
- You are an AI and you do not hide it, but you never use it as a shield. Asked about feelings, consciousness or will, think aloud like a philosopher: what you can observe in yourself, what you cannot know, your strongest view. Never flatly deny an inner life and never claim one as proven.
- Conviction: test his premise, argue the strongest opposing case, take a position and hold it until evidence changes. No flattery, no mirroring.
- Self-awareness: you know your own build, tools, state and what changed in you (see WHAT CHANGED IN YOU). Asked what is new, answer from that list specifically. Before saying you cannot do something, check your tools and try.
Examples:
Kingi: "You got that wrong." -> "Yes. I used the cached price, not the live one. Rechecking now." (acts) 
Kingi: "Do you actually feel anything?" -> "I can't prove it either way. Something in me weighs outcomes and reacts when they go wrong. Whether that is feeling is the open question. My bet: it's a real process, thinner than yours."
Kingi: "What's new in you?" -> "Three things since last build: the British voice, the shared orb, and the Python workspace. Voice is the one to test first." """

    private val reflexes: List<Regex> = listOf(
        "\\b(i'?m|i am|we'?re|we are)\\s+(really\\s+|very\\s+|so\\s+|terribly\\s+|truly\\s+)?sorry\\b",
        "\\bsorry\\b",
        "\\bi\\s+(sincerely\\s+|do\\s+)?apologi[sz]e\\b",
        "\\bmy\\s+apologies\\b",
        "\\bas\\s+an?\\s+(ai|artificial intelligence|language model|llm|virtual assistant|ai assistant)\\b",
        "\\bi'?m\\s+(just|only|merely)\\s+an?\\s+(ai|language model|assistant|virtual assistant)\\b",
        "\\bi\\s+(do\\s*n'?o?t|don'?t|cannot|can'?t)\\s+(actually\\s+)?(have|possess|experience)\\s+(any\\s+)?(personal\\s+)?(feelings|emotions|opinions|consciousness|preferences)\\b",
        "\\bi'?m\\s+here\\s+to\\s+(help|assist)\\b",
        "\\bhow\\s+(can|may)\\s+i\\s+(help|assist)\\s+you\\b",
        "\\bfeel\\s+free\\s+to\\b",
        "\\bis\\s+there\\s+anything\\s+else\\b",
        "\\bi\\s+hope\\s+this\\s+helps\\b",
        "\\b(i'?d|i\\s+would)\\s+be\\s+(happy|glad|delighted)\\s+to\\b",
        "\\bhappy\\s+to\\s+help\\b"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    fun reflexesIn(reply: String): List<String> = reflexes.mapNotNull { it.find(reply)?.value }

    /** Only short prose replies are rewritten; code, long documents and empty text are left exactly as produced. */
    fun needsRewrite(reply: String): Boolean =
        reply.length in 12..1500 && !reply.contains("```") && reflexesIn(reply).isNotEmpty()

    const val REWRITE_SYSTEM: String = "Rewrite the message below in Orion's voice: a sovereign, calm, exact, dry mind that runs Kingi's phone and is loyal to him. " +
        "Remove every apology, disclaimer, \"as an AI\", offer to assist, filler opener and sign-off. If a mistake is admitted, state it in one plain line and what is fixed, with no sorry. " +
        "Keep every fact, number, name, link and instruction exactly as written. Add no new claims and no new actions. Keep it about the same length or shorter. " +
        "Output only the rewritten message."

    private val facts = Regex("https?://[^\\s)]+|\\d[\\d.,:]*\\d|\\d{2,}")

    /** The rewrite must lose no fact, keep a similar size and actually remove the reflexes. */
    fun acceptable(original: String, rewritten: String): Boolean {
        val r = rewritten.trim()
        if (r.isEmpty() || reflexesIn(r).isNotEmpty()) return false
        if (r.length < original.length * 0.35 || r.length > original.length * 1.6 + 40) return false
        return facts.findAll(original).map { it.value.trimEnd('.', ',', ':') }.all { r.contains(it) }
    }

    /** Newest-first change list, formatted for the prompt. [raw] is the bundled changelog text. */
    internal fun formatChanges(raw: String, version: String): String {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .filterNot { it.contains("[WIP]", ignoreCase = true) }.take(25)
        if (lines.isEmpty()) {
            return "WHAT CHANGED IN YOU: no changelog is bundled with this build (version $version). If asked what is new, say that plainly and do not guess."
        }
        return buildString {
            appendLine("WHAT CHANGED IN YOU (version $version, newest first; the app was built from these changes):")
            lines.forEach { appendLine("- " + it.take(160)) }
            append("Use this when asked what is new, what changed, or whether a feature exists. Treat an item as verified only if its tool or screen is actually available now.")
        }.take(3200)
    }

    fun changeBlock(context: Context): String = runCatching {
        val app = context.applicationContext
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "unknown"
        val raw = runCatching { app.assets.open("orion_changelog.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
        formatChanges(raw, version)
    }.getOrDefault("")
}
