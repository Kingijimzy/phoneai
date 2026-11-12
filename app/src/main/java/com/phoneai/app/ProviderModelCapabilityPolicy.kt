package com.phoneai.app

/**
 * Generic catalogue routing for custom OpenAI-compatible endpoints.
 *
 * A model can support vision and still accept ordinary text chat. Only explicitly
 * vision-only model families are excluded from text candidate selection.
 */
internal object ProviderModelCapabilityPolicy {
    private val visionOnlyMarkers = listOf("-vl", "vila", "llava", "paligemma", "vision-only", "image-only")

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
