package com.phoneai.app

/**
 * Theory of Mind / Cognitive Pushback. A personality prompt is not consistent behaviour: telling the
 * model "disagree when he's wrong" is a suggestion it can skip under pressure to be agreeable. This is
 * the code-enforced version — a deterministic gate, not a line of instructions, that runs on every reply
 * to a weighty proposal and forces a second pass when the draft is bare agreement with no independent
 * position in it.
 *
 * Example this exists for:
 * Kingi: "I think we should change the entire Orion architecture."
 * A bare-agreement draft ("Sure, let's do that, I'll start now") gets caught here and sent back for a
 * real position before it ever reaches him — not because the prompt asked nicely, but because this
 * check refuses to let it through.
 */
internal object OrionCognitivePushback {
    private val weightyProposal = Regex(
        """(?i)\b(?:i think|i believe|i want to|let'?s|we should|maybe we should|can we)\b.{0,40}\b(?:change|replace|rewrite|redo|rebuild|scrap|throw out|start over|overhaul|tear down|ditch)\b.{0,40}\b(?:everything|the entire|the whole|all of|architecture|system|approach|codebase|platform)\b""" +
            """|\b(?:change|replace|rewrite|redo|rebuild|scrap|overhaul)\s+(?:the\s+)?entire\b"""
    )

    private val agreementOpener = Regex(
        """(?i)^\s*(?:sure|ok(?:ay)?|sounds good|got it|on it|will do|understood|yes[,.]?|agreed|absolutely|no problem|done)\b"""
    )
    private val independentPositionMarkers = Regex(
        """(?i)\b(?:but|however|instead|i'd rather|i think|i'd push back|i disagree|i'm not sure|the real problem|i suspect|before we|test that first|let's verify|that said|my concern|worth checking|rather than)\b"""
    )

    /** True when the user's message is the kind of weighty, system-level call that deserves an actual opinion. */
    fun isWeightyProposal(request: String): Boolean = weightyProposal.containsMatchIn(request)

    /**
     * True when the draft reply reads as compliance with no stated independent reasoning: either it opens
     * with a bare agreement phrase and never offers a but/instead/I-think-style counterweight, or it is too
     * short to contain any actual position (a real pushback needs at least a clause of reasoning).
     */
    fun looksLikeBareAgreement(reply: String): Boolean {
        val text = reply.trim()
        if (text.isBlank()) return false
        val hasPosition = independentPositionMarkers.containsMatchIn(text)
        if (hasPosition) return false
        val opensWithAgreement = agreementOpener.containsMatchIn(text.take(40))
        val tooShortForAPosition = text.length < 90
        return opensWithAgreement || tooShortForAPosition
    }

    const val PROMPT: String = "That reply agrees without saying why, or says too little to count as a real answer. " +
        "He asked about a weighty, system-level change, so he's owed an actual position, not compliance: " +
        "what you'd actually do with the request, what already exists that it would throw away or duplicate, " +
        "and the one thing you'd check or fix first instead if replacing everything isn't the right move. " +
        "If you genuinely agree after that reasoning, say so and say why — but the why has to be there."
}
