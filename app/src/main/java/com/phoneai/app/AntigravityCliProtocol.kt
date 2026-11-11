package com.phoneai.app

import org.json.JSONObject

/**
 * Command and event contract for Google's official Antigravity CLI.
 *
 * Personal Google-account entitlement is obtained by signing in through the CLI itself.
 * Orion must not read credentials from another application's private storage or treat
 * the presence of a Google account on Android as an Antigravity login.
 */
internal object AntigravityCliProtocol {
    const val EXECUTABLE = "agy"
    const val OFFICIAL_INSTALLER = "https://antigravity.google/cli/install.sh"

    data class Invocation(val argv: List<String>, val requiresExistingLogin: Boolean)

    fun installInvocation(): Invocation = Invocation(
        listOf("/bin/sh", "-lc", "curl -fsSL $OFFICIAL_INSTALLER | sh"),
        requiresExistingLogin = false
    )

    fun headlessPrompt(prompt: String, model: String? = null): Invocation {
        require(prompt.isNotBlank()) { "Antigravity prompt is required" }
        require(prompt.length <= 100_000) { "Antigravity prompt exceeds the supported input size" }
        return Invocation(
            buildList { add(EXECUTABLE); add("-p"); add(prompt); add("--output-format"); add("stream-json"); if (!model.isNullOrBlank()) { add("--model"); add(model) } },
            requiresExistingLogin = true
        )
    }

    fun listModelsInvocation(): Invocation = Invocation(listOf(EXECUTABLE, "models"), requiresExistingLogin = true)

    fun parseModelList(output: String): List<String> = output.lineSequence()
        .map { it.trim() }
        .mapNotNull { line ->
            Regex("^([a-zA-Z0-9][a-zA-Z0-9._-]{2,80})\\s+").find(line)?.groupValues?.get(1)
        }
        .distinct()
        .toList()

    data class Event(val type: String, val text: String = "", val error: String = "")

    /** Parses one JSONL event; malformed/non-JSON terminal output is not treated as a response. */
    fun parseStreamEvent(line: String): Event? {
        val value = runCatching { JSONObject(line) }.getOrNull() ?: return null
        val type = value.optString("type").ifBlank { value.optString("event") }
        if (type.isBlank()) return null
        val text = value.optString("text")
            .ifBlank { value.optString("content") }
            .ifBlank { value.optString("response") }\n            .ifBlank { value.optString("result") }\n            .ifBlank { value.optJSONObject("message")?.optString("content").orEmpty() }
        val error = value.optString("error")
            .ifBlank { value.optJSONObject("error")?.optString("message").orEmpty() }
        return Event(type, text, error)
    }

    fun isAuthenticationRequired(event: Event): Boolean {
        val searchable = (event.type + " " + event.error + " " + event.text).lowercase()
        return searchable.contains("authentication required") ||
            searchable.contains("not authenticated") ||
            searchable.contains("please sign in")
    }
}
