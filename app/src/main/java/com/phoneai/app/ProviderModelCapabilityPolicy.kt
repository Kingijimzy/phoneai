package com.phoneai.app

/**
 * Generic catalogue routing for custom OpenAI-compatible endpoints.
 *
 * A model can support vision and still accept ordinary text chat. Only explicitly
 * vision-only model families are excluded from text candidate selection.
 */
internal object ProviderModelCapabilityPolicy {
    private val visionOnlyMarkers = listOf("-vl", "vila", "llava", "paligemma", "vision-only", "image-only")

    /** Families that can never answer ordinary chat. Every picker, catalogue filter and saved-model check reads this one list. */
    val nonChatMarkers = listOf(
        "embed", "embedding", "guard", "rerank", "reward", "clip", "parse", "safety", "retriev", "bge",
        "whisper", "parakeet", "tts", "asr", "nemoguard", "riva", "fastpitch", "diffusion", "stable",
        "flux", "sdxl", "bria", "cosmos", "grounding", "segment", "ocr", "detect", "nvclip",
        "image", "imagen", "veo", "live", "transcribe", "translat", "audio", "moderation", "content-safety", "orpheus",
        "judge", "systemone", "classif", "pose-estimation", "video-generation"
    )

    fun isChatCapable(model: String): Boolean = model.isNotBlank() && nonChatMarkers.none { model.contains(it, ignoreCase = true) }

    /** A saved model is only evicted for these unmistakable non-chat families, so an odd but legitimate name is never lost. */
    private val hardNonChat = listOf("judge", "systemone", "classif", "embed", "rerank", "whisper", "tts", "moderation", "nemoguard")
    fun mustEvict(savedModel: String): Boolean = hardNonChat.any { savedModel.contains(it, ignoreCase = true) }

    /** A provider's 400/404 saying the model belongs to a different endpoint: no retry on this model can ever work. */
    fun isEndpointMismatch(body: String): Boolean {
        val b = body.lowercase()
        return listOf("served only on", "judge model", "/v1/systemone", "not a chat model", "does not support chat", "only supports the /v1/embeddings", "use the /v1/completions")
            .any { b.contains(it) }
    }

    fun textCandidates(models: List<String>): List<String> =
        models.filterNot { model -> visionOnlyMarkers.any { model.contains(it, ignoreCase = true) } }

    /**
     * Refresh volatile provider catalogues after model failures even in quota-saver mode.
     * Gemini model identifiers are retired independently of the saved key, so retrying
     * hard-coded IDs after a 404 is less reliable than asking the key's live /models endpoint.
     */
    fun shouldDiscoverAfterFailure(providerId: String, quotaSaverEnabled: Boolean): Boolean =
        !quotaSaverEnabled || providerId == "nvidia" || providerId == "gemini" || providerId.startsWith("custom_")
}
