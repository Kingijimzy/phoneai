package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Normalizes native tool-call envelopes from supported provider response formats.
 *
 * This codec deliberately does not dispatch calls. Every normalized call must still pass
 * ToolRegistry lookup, argument validation, objective checks, ToolGate and the shared executor.
 */
internal object OrionNativeToolCallCodec {
    data class Call(val id: String, val name: String, val arguments: JSONObject, val available: Boolean, val validationError: String? = null)
    data class Turn(val text: String, val calls: List<Call>)

    private const val MAX_CALLS = 8
    private const val MAX_ARGUMENT_CHARS = 32_000

    fun parse(providerId: String, body: String, isKnownTool: (String) -> Boolean): Turn {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return Turn("", emptyList())
        val text = StringBuilder()
        val calls = ArrayList<Call>()

        fun add(id: String, name: String, rawArguments: Any?) {
            if (calls.size >= MAX_CALLS || name.isBlank()) return
            val callId = id.take(160).ifBlank { "native-${calls.size + 1}" }
            val available = isKnownTool(name)
            val args = when (rawArguments) {
                is JSONObject -> rawArguments
                is String -> {
                    if (rawArguments.length > MAX_ARGUMENT_CHARS) {
                        calls.add(Call(callId, name, JSONObject(), available, "arguments exceed the 32 KB limit"))
                        return
                    }
                    runCatching { JSONObject(rawArguments) }.getOrNull()
                }
                else -> null
            }
            if (args == null) {
                calls.add(Call(callId, name, JSONObject(), available, "arguments must be a valid JSON object"))
                return
            }
            if (args.length() > 64) {
                calls.add(Call(callId, name, JSONObject(), available, "arguments exceed the 64-field limit"))
                return
            }
            calls.add(Call(callId, name, JSONObject(args.toString()), available))
        }

        when {
            providerId == "gemini" -> {
                val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
                val parts = candidate?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.has("text")) text.append(part.optString("text"))
                    val call = part.optJSONObject("functionCall") ?: continue
                    add("gemini-${i + 1}", call.optString("name"), call.opt("args"))
                }
            }
            providerId == "opencode-zen" -> {
                val output = root.optJSONArray("output") ?: JSONArray()
                for (i in 0 until output.length()) {
                    val item = output.optJSONObject(i) ?: continue
                    when (item.optString("type")) {
                        "function_call" -> add(
                            item.optString("call_id", item.optString("id")),
                            item.optString("name"),
                            item.opt("arguments")
                        )
                        else -> {
                            val parts = item.optJSONArray("content") ?: continue
                            for (j in 0 until parts.length()) {
                                val part = parts.optJSONObject(j) ?: continue
                                if (part.optString("type") == "output_text" || part.has("text")) {
                                    text.append(part.optString("text"))
                                }
                            }
                        }
                    }
                }
                if (text.isEmpty()) text.append(root.optString("output_text", ""))
            }
            providerId == "anthropic" || root.has("stop_reason") -> {
                val blocks = root.optJSONArray("content") ?: JSONArray()
                for (i in 0 until blocks.length()) {
                    val block = blocks.optJSONObject(i) ?: continue
                    when (block.optString("type")) {
                        "text" -> text.append(block.optString("text"))
                        "tool_use" -> add(block.optString("id"), block.optString("name"), block.opt("input"))
                    }
                }
            }
            else -> {
                val choices = root.optJSONArray("choices") ?: JSONArray()
                val message = choices.optJSONObject(0)?.optJSONObject("message") ?: JSONObject()
                val content = message.opt("content")
                if (content is String) text.append(content)
                val nativeCalls = message.optJSONArray("tool_calls") ?: JSONArray()
                for (i in 0 until nativeCalls.length()) {
                    val call = nativeCalls.optJSONObject(i) ?: continue
                    val fn = call.optJSONObject("function") ?: continue
                    add(call.optString("id"), fn.optString("name"), fn.opt("arguments"))
                }
            }
        }
        return Turn(text.toString(), calls)
    }
}
