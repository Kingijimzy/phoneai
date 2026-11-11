package com.phoneai.app

import org.json.JSONObject

/** Provider-neutral detection of explicit output-token exhaustion. */
internal object OrionResponseCompleteness {
    fun wasTruncated(providerId: String, body: String, anthropicStyle: Boolean = false): Boolean {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return false
        val reason = when {
            providerId == "gemini" -> root.optJSONArray("candidates")
                ?.optJSONObject(0)?.optString("finishReason").orEmpty()
            anthropicStyle || providerId == "anthropic" -> root.optString("stop_reason")
            providerId == "opencode-zen" -> root.optJSONObject("incomplete_details")?.optString("reason")
                ?: root.optJSONObject("response")?.optJSONObject("incomplete_details")?.optString("reason").orEmpty()
            else -> root.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason").orEmpty()
        }
        return reason.equals("length", true) ||
            reason.equals("max_tokens", true) ||
            reason.equals("max_output_tokens", true) ||
            reason.equals("MAX_TOKENS", true)
    }
}
