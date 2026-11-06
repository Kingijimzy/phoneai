package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Conservative redaction for task recovery metadata. This is deliberately a pure helper
 * so the privacy boundary can be tested without Android framework dependencies.
 */
internal object AgentPrivacyFilter {
    private const val MAX_COMMAND = 2000

    private fun redact(text: String, limit: Int): String = text
        .replace(Regex("(?i)\\b(bearer\\s+)[a-z0-9._~+/-]+=*")) { it.groupValues[1] + "[redacted]" }
        .replace(Regex("(?i)\\b(api[_-]?key|access[_-]?token|refresh[_-]?token|password|passcode|pin|secret)\\s*[:=]\\s*[^\\s,;]+")) { it.groupValues[1] + "=[redacted]" }
        .replace(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE), "[email redacted]")
        .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[URL redacted]")
        .replace(Regex("(?<![A-Za-z0-9])\\+?\\d[\\d ()-]{6,}\\d(?![A-Za-z0-9])"), "[number redacted]")
        .take(limit)

    private val sensitiveField = Regex("(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|password|passcode|pin|secret|authorization|credential|private[_-]?key)")
    private fun sanitizeJson(value: Any?, key: String = ""): Any? {
        if (key.isNotBlank() && sensitiveField.containsMatchIn(key)) return "[redacted]"
        return when (value) {
            is JSONObject -> {
                val safe = JSONObject()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val child = keys.next()
                    safe.put(child, sanitizeJson(value.opt(child), child))
                }
                safe
            }
            is JSONArray -> JSONArray().also { out ->
                for (index in 0 until value.length()) out.put(sanitizeJson(value.opt(index)))
            }
            is String -> safeOutput(value)
            JSONObject.NULL -> JSONObject.NULL
            else -> value
        }
    }

    /** Structured arguments are retained for owner audit only after field-aware secret redaction. */
    fun safeArguments(arguments: JSONObject): String =
        runCatching { sanitizeJson(arguments).toString().take(2400) }.getOrDefault("{\"redacted\":true}")

    fun safeCommand(command: String): String = redact(command.trim(), MAX_COMMAND)
    fun safeOutput(output: String): String = redact(output, 6000)

}
