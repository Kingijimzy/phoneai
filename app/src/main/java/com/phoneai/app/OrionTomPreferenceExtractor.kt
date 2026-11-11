package com.phoneai.app

/** Extracts only explicit owner preferences; it never guesses personality or private mental states. */
internal object OrionTomPreferenceExtractor {
    data class Observation(
        val dimension: String,
        val value: String,
        val confidence: Double,
        val evidence: String
    )

    private val detail = Regex(
        "(?i)\\b(?:i prefer|i want|i need)\\s+(brief|concise|short|detailed|thorough|step[- ]by[- ]step|in[- ]depth)\\s+(?:answers?|explanations?|responses?|instructions?)\\b"
    )
    private val initiative = Regex(
        "(?i)\\b(?:i want you to|i prefer you to|please)\\s+(be proactive|take initiative|act autonomously|ask before acting|wait for my approval)\\b"
    )
    private val tone = Regex(
        "(?i)\\b(?:i prefer|i want)\\s+(?:a |an )?(direct|calm|friendly|professional|casual|neutral)\\s+(?:tone|style|voice)\\b"
    )
    private val verify = Regex(
        "(?i)\\b(?:always|please)\\s+(verify|test|check)\\s+(?:the result|your work|the outcome|everything)\\b"
    )
    private val intent = Regex(
        "(?i)\\b(?:stop|don't|do not)\\s+(?:making|having)\\s+(?:me|orion|it)\\s+(?:read|parse|use|require)\\s+(?:user )?(?:commands?|prompts?|command syntax)\\b|" +
            "\\bteach (?:orion|it) to infer intent\\b|" +
            "\\b(?:stop|don't|do not)\\s+(?:making me|asking me to|requiring me to)\\s+(?:use commands?|switch modes?|repeat myself)\\b|" +
            "\\bunderstand what i mean from context\\b"
    )

    fun extract(text: String): List<Observation> {
        val clean = text.trim().take(900)
        if (clean.isBlank()) return emptyList()
        val out = linkedMapOf<String, Observation>()
        detail.find(clean)?.let {
            out["response_detail"] = Observation("response_detail", it.groupValues[1].lowercase(), 1.0, it.value)
        }
        initiative.find(clean)?.let {
            out["initiative"] = Observation("initiative", it.groupValues[1].lowercase(), 1.0, it.value)
        }
        tone.find(clean)?.let {
            out["tone"] = Observation("tone", it.groupValues[1].lowercase(), 1.0, it.value)
        }
        verify.find(clean)?.let {
            out["verification_style"] = Observation("verification_style", "verify results before reporting completion", 1.0, it.value)
        }
        intent.find(clean)?.let {
            out["interaction_style"] = Observation("interaction_style", "infer intent from conversational context; do not require command syntax or unnecessary mode switching", 1.0, it.value)
        }
        return out.values.toList()
    }
}
