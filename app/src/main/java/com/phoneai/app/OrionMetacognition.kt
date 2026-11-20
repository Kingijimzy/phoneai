package com.phoneai.app

/**
 * The uncertainty check. Orion grades its own answer 1-100; below [VERIFY_BELOW] it must verify with a real lookup
 * before answering. The grade is parsed out of the reply (never shown as text) and shown as a small meter in the UI.
 */
internal object OrionMetacognition {
    const val VERIFY_BELOW = 80

    data class Result(val text: String, val confidence: Int?)

    private val tag = Regex("(?im)^[ \\t]*\\[?[ \\t]*confidence[ \\t]*[:=][ \\t]*(\\d{1,3})[ \\t]*%?[ \\t]*\\]?[ \\t]*\\.?[ \\t]*$")

    /** Pull the confidence line off the reply (last one wins) and return the clean text. */
    fun parse(answer: String): Result {
        val matches = tag.findAll(answer).toList()
        if (matches.isEmpty()) return Result(answer.trim(), null)
        val pct = matches.last().groupValues[1].toIntOrNull()?.takeIf { it in 1..100 }
        return Result(answer.replace(tag, "").replace(Regex("\n{3,}"), "\n\n").trim(), pct)
    }

    /** Checking a greeting or a one-liner against the web is waste; check answers that assert something. */
    fun worthVerifying(question: String, answer: String): Boolean = question.trim().length >= 12 && answer.trim().length >= 40

    fun revisionPrompt(evidence: String): String =
        "You rated your last answer below $VERIFY_BELOW% confident, so here is fresh evidence from a web search:\n" + evidence.take(3500) +
            "\n\nRevise your answer using it. Fix anything the evidence contradicts, say plainly what is still uncertain, and finish with a line [confidence: NN]."

    /** The standing instruction injected into every prompt. */
    const val INSTRUCTION =
        "METACOGNITION: finish every substantive answer (not greetings or acknowledgements) with one last line `[confidence: NN]`, " +
            "NN = 1-100 honest certainty that the answer is correct and complete. Below 80, name what you are unsure of; Orion will verify it with a web search before the owner sees it."
}

/** The latest grade, for the UI meter. */
internal object OrionConfidence {
    data class Grade(val percent: Int, val verifiedBy: String, val atMs: Long)
    @Volatile var latest: Grade? = null
        private set
    fun record(percent: Int?, verifiedBy: String, now: Long = System.currentTimeMillis()) {
        latest = percent?.let { Grade(it, verifiedBy, now) }
    }
}
