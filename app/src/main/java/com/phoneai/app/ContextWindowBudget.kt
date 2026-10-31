package com.phoneai.app

/** Priority-aware context-window packing. Token estimates are conservative, not provider tokenization. */
internal object ContextWindowBudget {
    data class Packed(val text: String, val estimatedTokens: Int, val includedSections: Int, val omittedSections: Int)
    fun estimateTokens(text: String): Int = (text.length + 3) / 4
    fun priority(section: String): Int {
        val s = section.lowercase()
        return when {
            "task resume instructions" in s || "active objective" in s -> 110
            "orion central brain" in s || "shared executive contract" in s -> 105
            "relevant persistent memory" in s || "long-term memory" in s || "things you have learned about how this user" in s -> 100
            "previous discussions for this objective" in s || "decisions and unresolved questions" in s -> 95
            "orion local self-diagnostic" in s || "self-diagnostic" in s -> 90
            "relevant execution lessons" in s || "lessons from your own past runs" in s -> 85
            "capability gap" in s || "extension" in s -> 75
            "objective graph" in s || "persisted task centre" in s -> 75
            "soul engine" in s || "live inventory" in s -> 65
            else -> 50
        }
    }
    fun pack(sections: List<String>, maxTokens: Int = 4_000): Packed {
        val budget = maxTokens.coerceAtLeast(1)
        val usable = sections.mapIndexedNotNull { index, raw -> raw.trim().takeIf { it.isNotEmpty() }?.let { Triple(index, it, priority(it)) } }
        if (usable.isEmpty()) return Packed("", 0, 0, 0)
        val selected = HashMap<Int, String>()
        var remaining = budget
        val perSectionCap = maxOf(160, budget * 55 / 100)
        usable.sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenBy { it.first }).forEach { (index, section, _) ->
            if (remaining <= 0) return@forEach
            val allowedChars = minOf(remaining, perSectionCap) * 4
            val clipped = if (section.length > allowedChars) section.take(allowedChars) else section
            val cost = estimateTokens(clipped)
            if (cost > 0) { selected[index] = clipped; remaining -= cost }
        }
        val text = usable.mapNotNull { selected[it.first] }.joinToString("\n\n")
        return Packed(text, estimateTokens(text), selected.size, usable.size - selected.size)
    }
}
