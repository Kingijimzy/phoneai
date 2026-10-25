package com.phoneai.app

/** Fail-closed guard against accidentally selecting a billable provider/model. */
internal object ProviderBillingPolicy {
    private val meteredProviders = setOf(
        "nvidia", "mistral", "sambanova", "together", "fireworks", "huggingface",
        "deepseek", "openai", "anthropic"
    )

    fun requiresPaidOptIn(providerId: String, modelId: String, providerRequiresPaidOptIn: Boolean): Boolean =
        providerRequiresPaidOptIn ||
            providerId.lowercase() in meteredProviders ||
            (providerId.equals("openrouter", ignoreCase = true) && !modelId.trim().lowercase().endsWith(":free"))

    fun isAllowed(
        providerId: String,
        modelId: String,
        providerRequiresPaidOptIn: Boolean,
        allowPaidProviders: Boolean
    ): Boolean = allowPaidProviders || !requiresPaidOptIn(providerId, modelId, providerRequiresPaidOptIn)
}
