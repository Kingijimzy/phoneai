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

    /** Catalogue refresh occurs after a failed model request, not speculatively before every request. */
    fun shouldDiscoverAfterFailure(providerId: String, quotaSaverEnabled: Boolean): Boolean =
        !quotaSaverEnabled || providerId == "nvidia" || providerId.startsWith("custom_")
}
