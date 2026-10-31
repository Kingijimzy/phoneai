package com.phoneai.app

import android.content.Context

/** Extracts only explicit, durable self-descriptions. No model call and no background surveillance. */
internal object MemoryAutoIndexer {
    data class Candidate(val category: String, val text: String, val confidence: Double, val reason: String)
    private data class Rule(val pattern: Regex, val category: String, val confidence: Double, val reason: String)

    private val rules = listOf(
        Rule(Regex("(?i)\\b(?:remember that|keep in mind that)\\s+(.{8,260})"), "fact", 0.95, "explicit remember request"),
        Rule(Regex("(?i)\\b(?:i prefer|my preference is|i like|i dislike|i do not like|i want you to)\\s+(.{5,250})"), "preference", 0.82, "explicit preference"),
        Rule(Regex("(?i)\\b(?:i usually|i normally|i always|every day|every morning|every evening|each weekday|my daily routine is)\\s+(.{5,240})"), "routine", 0.82, "explicit routine or habit"),
        Rule(Regex("(?i)\\b(?:i am building|i'm building|i am working on|i'm working on|my current project is)\\s+(.{5,240})"), "project", 0.82, "explicit ongoing project"),
        Rule(Regex("(?i)\\b(?:my goal is|i am trying to|i'm trying to|i aim to)\\s+(.{5,240})"), "task", 0.78, "explicit goal")
    )

    fun extract(message: String): Candidate? {
        val clean = message.trim().replace(Regex("\\s+"), " ").take(500)
        if (clean.length !in 8..500 || clean.contains('?')) return null
        if (MemoryGovernancePolicy.sensitivity(clean) == "sensitive") return null
        val match = rules.firstNotNullOfOrNull { rule ->
            rule.pattern.find(clean)?.let { found ->
                found.groupValues.getOrNull(1)?.trim()?.trimEnd('.', '!', ';')
                    ?.takeIf { it.length >= 5 }?.let { rule to it }
            }
        } ?: return null
        val (rule, captured) = match
        val normalized = captured.replace(Regex("[.\\s]+$"), "").take(280)
        val text = when (rule.category) {
            "preference" -> "User preference: $normalized"
            "routine" -> "User routine: $normalized"
            "project" -> "Ongoing project: $normalized"
            "task" -> "User goal: $normalized"
            else -> normalized
        }
        return Candidate(rule.category, text.take(300), rule.confidence, rule.reason)
    }

    /** Store as an unapproved proposal. The owner remains the only authority for durable truth. */
    fun observe(context: Context, message: String): Memory? {
        val candidate = extract(message) ?: return null
        return MemoryStore.addObservedProposal(context, candidate.category, candidate.text, candidate.confidence, candidate.reason)
    }
}
