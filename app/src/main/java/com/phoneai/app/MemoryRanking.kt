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

    /** Ultron Cerebro's bounded character 3-gram feature, normalized for punctuation and whitespace. */
    internal fun characterTrigrams(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .flatMap { word -> if (word.length < 3) emptyList() else (0..word.length - 3).map { word.substring(it, it + 3) } }
            .take(60)

    /** Fuzzy sub-word overlap in [0,1], useful for typos and partial entity names. */
    fun trigramSimilarity(a: String, b: String): Double {
        val x = characterTrigrams(a).toSet()
        val y = characterTrigrams(b).toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size.toDouble()
    }

    /** Existing memory a new statement should replace: same category and nearly the same subject. */
    fun findSuperseded(existing: List<Memory>, category: String, text: String, threshold: Double = 0.6): Memory? =
        existing
            .filter { !it.pinned && it.category == category && !it.text.equals(text, true) }
            .map { memory ->
                memory to similarity(memory.text, text)
            }
            .filter { (memory, score) ->
                score >= threshold || GraphPolicy.hasSingleValuedConflict(memory.text, text)
            }
            .maxWithOrNull(
                compareBy<Pair<Memory, Double>> { if (GraphPolicy.hasSingleValuedConflict(it.first.text, text)) 1 else 0 }
                    .thenBy { it.second }
            )?.first

    private val concepts: List<Set<String>> = listOf(
        setOf("trade", "trading", "trader", "bybit", "forex", "deriv", "crypto", "chart", "swing", "position", "currency", "currencies", "exchange", "fx", "market"),
        setOf("music", "song", "play", "playlist", "artist", "album", "spotify", "youtube"),
        setOf("code", "coding", "program", "kotlin", "python", "script", "developer", "app", "build"),
        setOf("work", "job", "career", "company", "business", "employer", "office"),
        setOf("live", "lives", "living", "reside", "resides", "home", "house", "apartment", "city", "lagos", "address", "location", "based"),
        setOf("family", "wife", "husband", "mother", "father", "brother", "sister", "son", "daughter", "kid", "child"),
        setOf("food", "eat", "meal", "dinner", "lunch", "breakfast", "restaurant", "cook"),
        setOf("sleep", "wake", "morning", "night", "routine", "schedule", "alarm"),
        setOf("money", "budget", "salary", "pay", "price", "cost", "balance")
    )

    /** Cheap suffix stripping so "trading", "trader" and "trades" meet. */
    fun stem(word: String): String {
        val w = word.lowercase()
        if (w.length > 5 && w.endsWith("ies")) return w.dropLast(3) + "y"
        if (w.length > 4 && (w.endsWith("ves") || w.endsWith("des") || w.endsWith("ses") || w.endsWith("kes"))) {
            return w.dropLast(1)
        }
        if (w.length > 5 && w.endsWith("ing")) {
            val root = w.dropLast(3)
            return if (root.endsWith("d") || root.endsWith("v") || root.endsWith("s")) root + "e" else root
        }
        if (w.length > 5 && w.endsWith("er")) {
            val root = w.dropLast(2)
            return if (root.endsWith("d") || root.endsWith("v") || root.endsWith("s")) root + "e" else root
        }
        for (suffix in listOf("ers", "ed", "ches", "shes", "xes", "zes", "es", "s", "ly")) {
            if (w.length - suffix.length >= 4 && w.endsWith(suffix)) return w.dropLast(suffix.length)
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
        // Compare query tokens to individual memory tokens. Whole-sentence trigram Jaccard
        // dilutes a one-word typo across every unrelated word in the memory sentence.
        val fuzzy = if (matched == 0 && related == 0) {
            val bodyTokens = tokens(memory.text)
            words.maxOfOrNull { queryToken ->
                bodyTokens.maxOfOrNull { bodyToken -> trigramSimilarity(queryToken, bodyToken) } ?: 0.0
            } ?: 0.0
        } else 0.0
        if (matched == 0 && related == 0 && fuzzy < 0.24) return 0
        val fuzzyBonus = if (fuzzy >= 0.24) (fuzzy * 20).toInt().coerceAtLeast(1) else 0
        return matched * 10 + related * 5 + fuzzyBonus +
            (if (body.contains(normalized)) 20 else 0) + (if (memory.category == normalized) 5 else 0)
    }

    /**
     * What goes into the prompt: pinned memories always, then those relevant to the request,
     * then the most recent to fill the remaining space.
     */
    fun select(memories: List<Memory>, query: String?, limit: Int = 25, relevantLimit: Int = 10): List<Memory> {
        val chosen = LinkedHashMap<Int, Memory>()
        memories.filter { it.pinned }.sortedByDescending { it.updated }.forEach { chosen[it.id] = it }
        if (!query.isNullOrBlank()) {
            // Dense local embeddings participate in the actual prompt-recall path,
            // not only the Memory screen's explicit search operation.
            hybridRank(
                memories.filter { !it.pinned },
                { it.text + " " + it.category },
                query,
                relevantLimit
            ).forEach { if (chosen.size < limit) chosen.putIfAbsent(it.id, it) }
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
     * Deterministic on-device feature embedding. This is not a downloaded neural model:
     * word, phrase, character and domain-concept features are projected into a bounded
     * dense vector with signed feature hashing. It gives semantic-family recall without
     * network access, model files, or sending private memory to a provider.
     */
    private const val EMBEDDING_DIMENSIONS = 384
    private val embeddingCache = object : LinkedHashMap<String, FloatArray>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean = size > 512
    }

    private fun featureIndex(feature: String): Int = (feature.hashCode() and Int.MAX_VALUE) % EMBEDDING_DIMENSIONS
    private fun featureSign(feature: String): Float = if (feature.hashCode().ushr(1).rem(2) == 0) 1f else -1f

    private fun embedding(text: String): FloatArray = synchronized(embeddingCache) {
        embeddingCache[text]?.let { return@synchronized it.copyOf() }
        val vector = FloatArray(EMBEDDING_DIMENSIONS)
        fun add(feature: String, weight: Float) {
            val index = featureIndex(feature)
            vector[index] += featureSign(feature) * weight
        }
        val words = tokens(text).map(::stem).filter { it.isNotBlank() }
        words.forEach { add("w:$it", 1.0f) }
        words.zipWithNext().forEach { (a, b) -> add("p:$a:$b", 0.7f) }
        characterTrigrams(text).distinct().forEach { add("c:$it", 0.18f) }
        concepts.forEachIndexed { index, group ->
            if (words.any { word -> group.any { stem(it) == word } }) add("concept:$index", 1.35f)
        }
        val norm = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) for (i in vector.indices) vector[i] /= norm
        embeddingCache[text] = vector.copyOf()
        vector
    }

    /** Cosine similarity between private, deterministic, device-local dense feature vectors. */
    fun embeddingSimilarity(a: String, b: String): Double {
        val x = embedding(a)
        val y = embedding(b)
        return x.indices.sumOf { (x[it] * y[it]).toDouble() }.coerceIn(-1.0, 1.0)
    }

    /**
     * Local hybrid retrieval, adapted from Ultron Cerebro: BM25 + word TF-IDF cosine +
     * character-trigram TF-IDF cosine + dense feature-hash embedding cosine, fused with RRF.
     * All features are computed on-device; no network or external model is used.
     */
    fun <T> hybridRank(items: List<T>, textOf: (T) -> String, query: String, limit: Int = 10): List<T> {
        if (items.isEmpty() || limit <= 0) return emptyList()
        val queryTokens = tokens(query).toList()
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

        val queryGrams = characterTrigrams(query).groupingBy { it }.eachCount()
        val gramDocs = items.map { characterTrigrams(textOf(it)) }
        val gramDf = HashMap<String, Int>()
        gramDocs.forEach { grams -> grams.toSet().forEach { gramDf[it] = (gramDf[it] ?: 0) + 1 } }
        fun gramIdf(gram: String) = Math.log(1.0 + (n - (gramDf[gram] ?: 0) + .5) / ((gramDf[gram] ?: 0) + .5))
        val gramQueryWeights = queryGrams.mapValues { (gram, count) -> count * gramIdf(gram) }
        val gramQueryNorm = Math.sqrt(gramQueryWeights.values.sumOf { it * it }).coerceAtLeast(1e-9)
        val trigramCosine = gramDocs.map { grams ->
            val tf = grams.groupingBy { it }.eachCount()
            val docNorm = Math.sqrt(tf.entries.sumOf { (gram, count) ->
                val weight = count * gramIdf(gram); weight * weight
            }).coerceAtLeast(1e-9)
            gramQueryWeights.entries.sumOf { (gram, weight) ->
                weight * (tf[gram] ?: 0) * gramIdf(gram)
            } / (gramQueryNorm * docNorm)
        }

        fun ranks(scores: List<Double>): Map<Int, Int> =
            scores.withIndex().filter { it.value > 0.0 }.sortedByDescending { it.value }
                .withIndex().associate { it.value.index to it.index + 1 }
        val rankA = ranks(bm25)
        val rankB = ranks(cosine)
        val denseEmbeddings = items.map { embeddingSimilarity(query, textOf(it)) }
        val rankC = ranks(trigramCosine)
        val rankD = ranks(denseEmbeddings)
        return items.indices
            .filter { it in rankA || it in rankB || it in rankC || it in rankD }
            .map { i ->
                i to (1.0 / (60 + (rankA[i] ?: 1000)) +
                    1.0 / (60 + (rankB[i] ?: 1000)) +
                    1.0 / (60 + (rankC[i] ?: 1000)) +
                    1.0 / (60 + (rankD[i] ?: 1000)))
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { items[it.first] }
    }

    private fun tokenList(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in stop }
}
