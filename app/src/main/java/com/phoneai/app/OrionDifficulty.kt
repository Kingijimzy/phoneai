package com.phoneai.app

import kotlin.math.ln

/**
 * Think harder when the question is harder, on any provider.
 *
 * Two small pure pieces. [tierFor] estimates how demanding a request is from the shape of the work (length, how many
 * steps are chained, reasoning verbs, code, a correction after a miss, how big the open objective already is); it never
 * decides what the request *means* or whether tools are used, only how much model to spend. [pick] then chooses, from the
 * provider's own cached catalogue only, a clearly stronger model for hard turns or a clearly lighter one for trivial
 * turns, and otherwise keeps the owner's selected model. It never invents a model id and never picks a non-chat model.
 */
internal object OrionDifficulty {
    private val reasoning = listOf(
        "why", "explain", "compare", "trade-off", "tradeoff", "design", "architect", "debug", "diagnose", "root cause",
        "strategy", "optimi", "refactor", "analy", "investigate", "research", "evaluate", "prove", "derive", "audit", "review", "plan"
    )
    private val explicitDeep = Regex("(?i)\\b(deep dive|think (hard|carefully|deeply)|thoroughly|step by step|full (analysis|review|report)|end to end|from scratch)\\b")
    private val chained = Regex("(?i)(\\bthen\\b|\\bafter that\\b|\\bnext\\b|;|\\n\\s*(\\d+[.)]|[-*])\\s)")
    private val codey = Regex("(?i)(```|exception|stack ?trace|\\.(kt|py|js|ts|java|json|yml|gradle)\\b|\\bnull ?pointer\\b)")

    fun score(request: String, hasImage: Boolean = false, correction: Boolean = false, objectiveNodes: Int = 0): Int {
        val t = request.trim()
        if (t.isEmpty() || hasImage) return 2
        var s = 0
        s += when { t.length > 600 -> 2; t.length > 240 -> 1; else -> 0 }
        val steps = chained.findAll(t).count()
        s += when { steps >= 3 -> 3; steps >= 1 -> 1; else -> 0 }
        val lower = t.lowercase()
        s += reasoning.count { lower.contains(it) }.coerceAtMost(3)
        if (codey.containsMatchIn(t)) s += 1
        if (explicitDeep.containsMatchIn(t)) s += 3
        if (correction) s += 2
        if (objectiveNodes >= 4) s += 1
        return s
    }

    fun tierFor(request: String, hasImage: Boolean = false, correction: Boolean = false, objectiveNodes: Int = 0): ModelRouter.Tier {
        val s = score(request, hasImage, correction, objectiveNodes)
        val words = request.trim().split(Regex("\\s+")).size
        return when {
            s >= 5 -> ModelRouter.Tier.DEEP
            s == 0 && words <= 12 && !hasImage -> ModelRouter.Tier.FAST
            else -> ModelRouter.Tier.CHAT
        }
    }

    private val deepWords = listOf("ultra", "opus", "max", "large", "reasoner", "thinking", "r1", "pro", "405b", "480b", "671b")
    private val fastWords = listOf("flash", "lite", "lightning", "mini", "nano", "haiku", "small", "instant", "turbo", "tiny", "scout")
    private val params = Regex("(\\d+(?:\\.\\d+)?)\\s*b(?![a-z])")

    /** Relative strength from the model's own name: parameter count when it states one, plus family words. Unknown names sit in the middle. */
    fun power(model: String): Double {
        val m = model.lowercase()
        val total = params.find(m)?.groupValues?.get(1)?.toDoubleOrNull()
        var p = if (total != null) ln(total + 1.0) * 10.0 else 40.0
        fun has(w: String) = Regex("(^|[^a-z0-9])" + Regex.escape(w) + "([^a-z0-9]|$)").containsMatchIn(m)
        if (deepWords.any { has(it) }) p += 15.0
        if (fastWords.any { has(it) }) p -= 15.0
        return p
    }

    private const val MARGIN = 8.0

    /** A catalogue model for this tier, or null to keep [current]. Only chat-capable ids from [catalogue] are ever returned. */
    fun pick(tier: ModelRouter.Tier, current: String, catalogue: List<String>,
             quality: (String) -> Double? = { null }, firstTokenMs: (String) -> Double? = { null }): String? {
        if (tier == ModelRouter.Tier.CHAT) return null
        // Measured evidence outranks guessing from the model name whenever the registry has it.
        val usableNow = catalogue.filter { ProviderModelCapabilityPolicy.isChatCapable(it) }.distinct()
        if (tier == ModelRouter.Tier.DEEP) {
            val measured = usableNow.mapNotNull { m -> quality(m)?.let { m to it } }
            val best = measured.maxByOrNull { it.second }
            val cur = quality(current)
            if (best != null && best.first != current && (cur == null || best.second >= cur + 0.05)) return best.first
            if (best != null && cur != null) return null
        }
        if (tier == ModelRouter.Tier.FAST) {
            val measured = usableNow.mapNotNull { m -> firstTokenMs(m)?.let { m to it } }
            val best = measured.minByOrNull { it.second }
            val cur = firstTokenMs(current)
            if (best != null && best.first != current && (cur == null || best.second <= cur * 0.8)) return best.first
            if (best != null && cur != null) return null
        }
        val usable = catalogue.filter { ProviderModelCapabilityPolicy.isChatCapable(it) }.distinct()
        if (usable.isEmpty()) return null
        val base = power(current)
        return when (tier) {
            ModelRouter.Tier.DEEP -> usable.maxByOrNull { power(it) }?.takeIf { power(it) >= base + MARGIN && it != current }
            ModelRouter.Tier.FAST -> usable.minByOrNull { power(it) }?.takeIf { power(it) <= base - MARGIN && it != current }
            else -> null
        }
    }
}
