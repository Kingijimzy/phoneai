package com.phoneai.app

import android.content.Context
import org.json.JSONArray

/**
 * Common interface for every AI model provider Orion can route to.
 *
 * Nim is the sole production implementation; this interface keeps provider-specific
 * HTTP/streaming details behind a single stable contract so the rest of Orion never
 * depends on a concrete class. Providers are swapped in Settings (Prefs.provider / setProvider)
 * and the selected provider is used for every chat/screen-vision turn unless Auto-switch
 * rotates to a backup after a recoverable failure.
 *
 * Failure recovery is bounded: same-provider model recovery runs first, then up to
 * MAX_BACKUP_PROVIDERS backup providers, all within the shared 60-request command budget.
 * Cancellation, authentication errors, billing blocks, and general network-unavailable
 * conditions are never rotated through — they fail closed and are reported honestly.
 */
internal data class OrionModelToolCall(
    val id: String,
    val name: String,
    val arguments: org.json.JSONObject,
    val available: Boolean = true,
    val validationError: String? = null,
    /** Gemini requires this opaque provider token to be echoed on the matching functionCall. */
    val thoughtSignature: String? = null
)

internal data class OrionModelTurn(
    val text: String,
    val toolCalls: List<OrionModelToolCall> = emptyList(),
    /** False when the selected provider cannot expose Orion's native tool protocol. */
    val toolCallingAvailable: Boolean = true
)

internal interface OrionModelProvider {
    /** One bounded chat turn. Returns the assistant's reply text or throws AiException/RuntimeException. */
    fun chat(
        messages: JSONArray,
        vision: Boolean,
        maxTokens: Int = 32768,
        temperature: Double = 0.2,
        fastResponse: Boolean = false,
        onDelta: ((String) -> Unit)? = null
    ): String

    /**
     * Structured tool-capable turn. Implementations that do not support native calls remain
     * text-compatible; production Nim overrides this and preserves provider-native call data.
     */
    fun chatTurn(
        messages: JSONArray,
        vision: Boolean,
        maxTokens: Int = 8192,
        temperature: Double = 0.2,
        fastResponse: Boolean = false,
        tools: List<ToolDefinition> = emptyList(),
        onDelta: ((String) -> Unit)? = null,
        onToolCall: ((String, org.json.JSONObject) -> String)? = null
    ): OrionModelTurn = OrionModelTurn(
        chat(messages, vision, maxTokens, temperature, fastResponse, onDelta)
    )

    /** Probe whether a specific model on a provider can answer chat; empty string means OK. */
    fun testModel(provider: Provider, model: String, vision: Boolean): String

    /** Every model id this provider's key can see (billing-filtered, cached 10 min). */
    fun allModels(provider: Provider): List<String>

    /** Abort the in-flight request and prevent new ones from attaching. */
    fun cancel()

    /** Resume after cancellation. */
    fun resume()

    /** AI requests used by the current command (for the cost line). */
    fun requestsUsed(): Int

    /** Start a fresh request budget and identical-failure counter for one user command. */
    fun beginCommand()
}

/** Nim satisfies the common provider interface. No provider-specific logic leaks past it. */
internal object OrionModelProviderFactories {
    fun create(ctx: Context, commandMode: Boolean = false, sharedBudget: CommandRequestBudget? = null): OrionModelProvider =
        AccountAwareModelProvider(ctx, Nim(ctx, commandMode, sharedBudget), commandMode)
}

/** NVIDIA is a first-class cloud provider; it is treated like every other OpenAI-compatible
 *  endpoint behind the same interface. NVIDIA-specific thinking-disable flags and model
 *  catalogue quirks live inside Nim, not behind a separate code path. */
internal object NVIDIA_PROVIDER {
    const val ID = "nvidia"
    const val LABEL = "NVIDIA API Catalog"
    const val BASE = "https://integrate.api.nvidia.com/v1"
}

/** Local model fallback: Ollama at 127.0.0.1:11434 (OpenAI-compatible, no network required
 *  once the model is loaded). It never sends data off-device and is always eligible. */
internal object LOCAL_PROVIDER {
    const val ID = "local"
    const val LABEL = "Local AI (Ollama endpoint)"
    const val BASE = "http://127.0.0.1:11434/v1"
}
