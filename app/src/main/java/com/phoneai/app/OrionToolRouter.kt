package com.phoneai.app

/**
 * Runtime tool selection. The model is no longer left to scan 70-odd tools: the request is matched against every
 * tool's name and contract (IDF-weighted, so rare words like "screenshot" outweigh common ones like "file"), then
 * scaled by measured reliability on this phone. The ranked top few go into the turn context as routes to start with.
 * Pure and deterministic; the model still decides, but starts from evidence.
 */
internal object OrionToolRouter {
    data class Route(val tool: String, val score: Double)

    private val stop = setOf("the", "and", "for", "with", "that", "this", "from", "into", "your", "you", "can", "use", "using", "get", "make", "please", "then", "when", "what", "how", "have", "has", "all", "any", "out", "about", "need", "want", "just", "like", "also", "its", "are", "not", "but", "does", "tool", "tools", "orion")

    internal fun tokens(text: String): List<String> =
        Regex("[a-z0-9]+").findAll(text.lowercase()).map { it.value }.filter { it.length >= 3 && it !in stop }.map { stem(it) }.toList()

    private fun stem(w: String): String = when {
        w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
        w.length > 4 && w.endsWith("es") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") -> w.dropLast(1)
        else -> w
    }

    private class Index(val docs: Map<String, Map<String, Double>>, val idf: Map<String, Double>)

    private fun build(tools: List<ToolDefinition>): Index {
        val docs = tools.associate { t ->
            val weights = HashMap<String, Double>()
            tokens(t.name.replace('_', ' ')).forEach { weights[it] = (weights[it] ?: 0.0) + 3.0 }
            tokens(t.description.ifBlank { t.promptLine }).forEach { weights[it] = (weights[it] ?: 0.0) + 1.0 }
            t.name to weights
        }
        val n = docs.size.coerceAtLeast(1).toDouble()
        val df = HashMap<String, Int>()
        docs.values.forEach { d -> d.keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
        return Index(docs, df.mapValues { Math.log(1.0 + n / it.value) })
    }

    private val defaultIndex by lazy { build(ToolRegistry.all) }

    /** Reliability from measured outcomes: unknown tools sit at 0.75, proven ones rise toward 1.2, failing ones fall toward 0.3. */
    internal fun reliability(ok: Int, failed: Int): Double {
        val n = ok + failed
        if (n < 2) return 0.75
        val rate = (ok + 1.0) / (n + 2.0)
        return 0.3 + 0.9 * rate
    }

    internal fun rank(request: String, tools: List<ToolDefinition> = ToolRegistry.all, stats: Map<String, Triple<Int, Int, Long>> = OrionForesight.stats(), k: Int = 4): List<Route> {
        val q = tokens(request).distinct()
        if (q.isEmpty()) return emptyList()
        val index = if (tools === ToolRegistry.all) defaultIndex else build(tools)
        val scored = index.docs.map { (name, weights) ->
            val raw = q.sumOf { t -> (weights[t] ?: 0.0) * (index.idf[t] ?: 0.0) }
            val s = stats[name]
            Route(name, raw * reliability(s?.first ?: 0, s?.second ?: 0))
        }.filter { it.score > 0.0 }.sortedByDescending { it.score }
        val best = scored.firstOrNull()?.score ?: return emptyList()
        return scored.filter { it.score >= best * 0.45 }.take(k)
    }

    fun summary(request: String): String {
        val routes = runCatching { rank(request) }.getOrDefault(emptyList())
        if (routes.isEmpty()) return ""
        return "ROUTES FOR THIS REQUEST (ranked by fit to the request and measured reliability on this phone; start with the first, switch only on evidence): " + routes.joinToString(" > ") { it.tool }
    }
}
