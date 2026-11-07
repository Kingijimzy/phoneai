package com.phoneai.app

import kotlin.math.sqrt

/**
 * On-device text embedding, following the approach of Ultron's memory/cerebro_graph.py: L2-normalised
 * bag-of-tokens vectors with word and character 3-gram features, compared by cosine similarity.
 * Features are hashed into a fixed width so no vocabulary has to be rebuilt as memory grows, and word stems
 * plus concept groups let paraphrases ("which club do I support" / "my favourite team") land close together.
 * Nothing leaves the device, and no model download is needed. A neural sentence model (TFLite) could replace
 * embed() later without changing callers.
 */
internal object OrionEmbedding {
    const val DIM = 512
    private const val WORD = 1.0f
    private const val CONCEPT = 0.9f
    private const val TRIGRAM = 0.25f

    fun embed(text: String): FloatArray {
        val v = FloatArray(DIM)
        for (token in MemoryRanking.tokens(text)) {
            val stem = MemoryRanking.stem(token)
            add(v, "w:$stem", WORD)
            MemoryRanking.conceptIndex(stem)?.let { add(v, "c:$it", CONCEPT) }
            if (token.length >= 4) for (i in 0..token.length - 3) add(v, "g:" + token.substring(i, i + 3), TRIGRAM)
        }
        var norm = 0.0
        for (x in v) norm += (x * x).toDouble()
        if (norm > 1e-9) {
            val inv = (1.0 / sqrt(norm)).toFloat()
            for (i in v.indices) v[i] *= inv
        }
        return v
    }

    private fun add(v: FloatArray, feature: String, weight: Float) {
        val h = feature.hashCode()
        val mixed = h xor (h ushr 16)
        val index = (mixed and 0x7fffffff) % DIM
        v[index] += if ((h ushr 7) and 1 == 0) weight else -weight
    }

    /** Cosine similarity of two embed() results (they are already unit length). */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        for (i in 0 until minOf(a.size, b.size)) dot += (a[i] * b[i]).toDouble()
        return dot
    }

    fun similarity(a: String, b: String): Double = cosine(embed(a), embed(b))

    /** Items ordered by meaning-similarity to the query; weak matches below [minScore] are dropped. */
    fun <T> rank(items: List<T>, textOf: (T) -> String, query: String, limit: Int = 6, minScore: Double = 0.12): List<Pair<T, Double>> {
        val q = embed(query)
        return items.map { it to cosine(q, embed(textOf(it))) }
            .filter { it.second >= minScore }
            .sortedByDescending { it.second }
            .take(limit)
    }
}
