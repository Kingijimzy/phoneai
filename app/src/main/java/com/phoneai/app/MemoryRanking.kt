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

    private val concepts: List<Set<String>> = listOf(
        setOf("trade", "trading", "trader", "bybit", "forex", "deriv", "crypto", "chart", "swing", "position"),
        setOf("music", "song", "play", "playlist", "artist", "album", "spotify", "youtube"),
        setOf("code", "coding", "program", "kotlin", "python", "script", "developer", "app", "build"),
        setOf("work", "job", "career", "company", "business", "employer", "office"),
        setOf("live", "home", "city", "lagos", "address", "location", "based"),
        setOf("family", "wife", "husband", "mother", "father", "brother", "sister", "son", "daughter", "kid", "child"),
        setOf("food", "eat", "meal", "dinner", "lunch", "breakfast", "restaurant", "cook"),
        setOf("sleep", "wake", "morning", "night", "routine", "schedule", "alarm"),
        setOf("money", "budget", "salary", "pay", "price", "cost", "balance")
    )

    /** Cheap suffix stripping so "trading", "trader" and "trades" meet. */
    fun stem(word: String): String {
        var w = word.lowercase()
        for (suffix in listOf("ings", "ing", "ers", "er", "ed", "es", "s", "ly")) {
            if (w.length - suffix.length >= 4 && w.endsWith(suffix)) { w = w.dropLast(suffix.length); break }
        }
        return w
    }

    private fun relatedTo(word: String, bodyWords: Set<String>): Boolean {
        val stem = stem(word)
        if (bodyWords.any { stem(it) == stem }) return true
        val group = concepts.firstOrNull { g -> g.any { stem(it) == stem } } ?: return false
        return bodyWords.any { b -> group.any { stem(it) == stem(b) } }
    }

    fun score(memory: Memory, query: String): Int {
        val normalized = query.trim().lowercase()
        val words = tokens(query)
        if (words.isEmpty()) return 0
        val body = memory.text.lowercase()
        val matched = words.count { body.contains(it) || memory.category.contains(it) }
        val bodyWords = tokens(memory.text)
        val related = words.count { !(body.contains(it) || memory.category.contains(it)) && relatedTo(it, bodyWords) }
        if (matched == 0 && related == 0) return 0
        return matched * 10 + related * 5 + (if (body.contains(normalized)) 20 else 0) + (if (memory.category == normalized) 5 else 0)
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

    /** Filter first, then rank. Pinned memories outside the allowlist must never bypass a specialist scope. */
    fun selectCategories(
        memories: List<Memory>,
        allowedCategories: Set<String>,
        query: String?,
        limit: Int = 25,
        relevantLimit: Int = 10
    ): List<Memory> = select(
        memories.filter { it.category in allowedCategories },
        query,
        limit,
        relevantLimit
    )

    /**
     * Local hybrid retrieval, in the spirit of vector + keyword search with reciprocal rank fusion:
     * TF-IDF cosine similarity (semantic-ish, order of rarity) fused with BM25 keyword ranking.
     * No network, no embeddings model; works on short notes and document chunks.
     */
    fun <T> hybridRank(items: List<T>, textOf: (T) -> String, query: String, limit: Int = 10): List<T> {
        val queryTokens = tokens(query).toList()
        if (queryTokens.isEmpty() || items.isEmpty()) return emptyList()
        val docs = items.map { tokenList(textOf(it)) }
        val n = docs.size.toDouble()
        val avgLen = docs.map { it.size }.average().coerceAtLeast(1.0)
        val df = HashMap<String, Int>()
        docs.forEach { d -> d.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        fun idf(t: String) = Math.log(1.0 + (n - (df[t] ?: 0) + .5) / ((df[t] ?: 0) + .5))
        val bm25 = docs.map { d ->
            val tf = d.groupingBy { it }.eachCount()
            queryTokens.sumOf { t ->
                val f = (tf[t] ?: 0).toDouble()
                if (f == 0.0) 0.0 else idf(t) * (f * 2.2) / (f + 1.2 * (.25 + .75 * d.size / avgLen))
            }
        }
        val qVec = queryTokens.associateWith { idf(it) }
        val qNorm = Math.sqrt(qVec.values.sumOf { it * it }).coerceAtLeast(1e-9)
        val cosine = docs.map { d ->
            val tf = d.groupingBy { it }.eachCount()
            val dNorm = Math.sqrt(tf.entries.sumOf { (t, c) -> (c * idf(t)).let { it * it } }).coerceAtLeast(1e-9)
            queryTokens.sumOf { t -> (qVec[t] ?: 0.0) * (tf[t] ?: 0) * idf(t) } / (qNorm * dNorm)
        }
        fun ranks(scores: List<Double>): Map<Int, Int> =
            scores.withIndex().filter { it.value > 0.0 }.sortedByDescending { it.value }
                .withIndex().associate { it.value.index to it.index + 1 }
        val rankA = ranks(bm25)
        val rankB = ranks(cosine)
        return items.indices
            .filter { it in rankA || it in rankB }
            .map { i -> i to (1.0 / (60 + (rankA[i] ?: 1000)) + 1.0 / (60 + (rankB[i] ?: 1000))) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { items[it.first] }
    }

    private fun tokenList(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in stop }
}
