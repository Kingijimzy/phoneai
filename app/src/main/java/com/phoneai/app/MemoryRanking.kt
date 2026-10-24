package com.phoneai.app

/** Pure memory selection/similarity helpers so recall behaviour is unit-testable without Android. */
internal object MemoryRanking {
    private val stop = setOf("the", "and", "for", "with", "that", "this", "you", "your", "are", "was", "has", "have", "not", "but", "can", "his", "her", "its", "our", "who", "what", "when", "how")

    fun tokens(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in stop }.toSet()

    /** Word-overlap similarity 0..1 (Jaccard). */
    fun similarity(a: String, b: String): Double {
        val x = tokens(a)
        val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size.toDouble()
    }

    /** Existing memory a new statement should replace: same category and nearly the same subject. */
    fun findSuperseded(existing: List<Memory>, category: String, text: String, threshold: Double = 0.6): Memory? =
        existing
            .filter { !it.pinned && it.category == category && !it.text.equals(text, true) }
            .map { it to similarity(it.text, text) }
            .filter { it.second >= threshold }
            .maxByOrNull { it.second }?.first

    fun score(memory: Memory, query: String): Int {
        val normalized = query.trim().lowercase()
        val words = tokens(query)
        if (words.isEmpty()) return 0
        val body = memory.text.lowercase()
        val matched = words.count { body.contains(it) || memory.category.contains(it) }
        if (matched == 0) return 0
        return matched * 10 + (if (body.contains(normalized)) 20 else 0) + (if (memory.category == normalized) 5 else 0)
    }

    /**
     * What goes into the prompt: pinned memories always, then those relevant to the request,
     * then the most recent to fill the remaining space.
     */
    fun select(memories: List<Memory>, query: String?, limit: Int = 25, relevantLimit: Int = 10): List<Memory> {
        val chosen = LinkedHashMap<Int, Memory>()
        memories.filter { it.pinned }.sortedByDescending { it.updated }.forEach { chosen[it.id] = it }
        if (!query.isNullOrBlank()) {
            memories.filter { !it.pinned }
                .map { it to score(it, query) }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<Memory, Int>> { it.second }.thenByDescending { it.first.updated })
                .take(relevantLimit)
                .forEach { if (chosen.size < limit) chosen.putIfAbsent(it.first.id, it.first) }
        }
        memories.sortedByDescending { it.updated }.forEach { if (chosen.size < limit) chosen.putIfAbsent(it.id, it) }
        return chosen.values.toList()
    }
}
