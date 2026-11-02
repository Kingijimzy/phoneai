package com.phoneai.app

/**
 * Picks a model size for each trading-chat message: a light model for short simple questions,
 * the standard model for normal analysis, a large model for rare heavy jobs. Only changes the model
 * on the NVIDIA provider; every other provider keeps whatever the person selected. Pure.
 */
internal object ModelRouter {
    enum class Tier { FAST, CHAT, DEEP }

    const val FAST_MODEL = "nvidia/nemotron-3.5-lightning-30b-a3b"
    const val DEEP_MODEL = "nvidia/nemotron-3-ultra-550b-a55b"

    private val deep = Regex("""(?i)\b(weekly review|review my week|monthly review|post-?mortem|deep dive|full (analysis|review|report)|rewrite my (playbook|strategy|rules)|overhaul)\b""")
    private val analysis = Regex("""(?i)(setup|strateg|forecast|simulat|backtest|analy|review|risk|position|trade|why|plan|entry|stop|habit|outlook|brief|news|match|playbook|wallet|memecoin|funding)""")

    fun tierFor(text: String, hasImage: Boolean): Tier {
        if (hasImage) return Tier.CHAT
        val t = text.trim()
        if (deep.containsMatchIn(t)) return Tier.DEEP
        if (t.length <= 40 && !analysis.containsMatchIn(t)) return Tier.FAST
        return Tier.CHAT
    }

    /** The model to force, or null to keep the person's own choice. */
    fun modelFor(
        tier: Tier,
        providerId: String,
        vision: Boolean,
        routingOn: Boolean,
        availableModels: Set<String> = emptySet()
    ): String? {
        if (!routingOn || vision || providerId != "nvidia") return null
        val candidate = when (tier) { Tier.FAST -> FAST_MODEL; Tier.DEEP -> DEEP_MODEL; Tier.CHAT -> return null }
        // Never route to a guessed/stale model ID. Use only a model returned by the provider's cached catalogue.
        return candidate.takeIf { it in availableModels }
    }
}
