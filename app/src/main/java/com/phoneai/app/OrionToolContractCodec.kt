package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts the authoritative ToolRegistry prompt contracts into provider-native JSON schemas.
 *
 * Signatures are parsed conservatively: unknown argument types remain strings rather than
 * being guessed as executable objects. Runtime dispatch remains the final validator.
 */
internal object OrionToolContractCodec {
    private data class Field(val name: String, val schema: JSONObject, val required: Boolean)

    fun openAi(tools: List<ToolDefinition>, descriptionLimit: Int = 1000): JSONArray = JSONArray().apply {
        tools.forEach { tool ->
            val schema = parameters(tool)
            put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description.take(descriptionLimit.coerceIn(80, 1000)))
                    .put("parameters", schema)))
        }
    }

    /** Responses API uses a flat function object rather than chat.completions' nested function. */
    fun responses(tools: List<ToolDefinition>, descriptionLimit: Int = 1000): JSONArray = JSONArray().apply {
        tools.forEach { tool ->
            put(JSONObject()
                .put("type", "function")
                .put("name", tool.name)
                .put("description", tool.description.take(1000))
                .put("parameters", parameters(tool))
                .put("strict", false))
        }
    }

    fun anthropic(tools: List<ToolDefinition>, descriptionLimit: Int = 1000): JSONArray = JSONArray().apply {
        tools.forEach { tool ->
            put(JSONObject()
                .put("name", tool.name)
                .put("description", tool.description.take(1000))
                .put("input_schema", parameters(tool)))
        }
    }

    fun gemini(tools: List<ToolDefinition>, descriptionLimit: Int = 1000): JSONArray = JSONArray().apply {
        put(JSONObject().put("functionDeclarations", JSONArray().apply {
            tools.forEach { tool ->
                put(JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description.take(1000))
                    .put("parameters", geminiParameters(tool)))
            }
        }))
    }

    private fun geminiParameters(tool: ToolDefinition): JSONObject {
        fun convert(source: JSONObject): JSONObject {
            val type = when (source.optString("type").lowercase()) {
                "object" -> "OBJECT"
                "array" -> "ARRAY"
                "integer" -> "INTEGER"
                "number" -> "NUMBER"
                "boolean" -> "BOOLEAN"
                else -> "STRING"
            }
            val out = JSONObject().put("type", type)
            source.optJSONObject("properties")?.let { props ->
                val converted = JSONObject()
                val keys = props.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    converted.put(key, convert(props.optJSONObject(key) ?: JSONObject()))
                }
                out.put("properties", converted)
            }
            source.optJSONObject("items")?.let { out.put("items", convert(it)) }
            source.optJSONArray("required")?.let { out.put("required", it) }
            source.optJSONArray("enum")?.let { out.put("enum", it) }
            return out
        }
        return convert(parameters(tool))
    }

    internal fun parameters(tool: ToolDefinition): JSONObject =
        objectSchema(parseFields(signature(tool.promptLine)))

    private fun objectSchema(fields: List<Field>): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        fields.forEach { field ->
            properties.put(field.name, field.schema)
            if (field.required) required.put(field.name)
        }
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", required)
            .put("additionalProperties", false)
    }

    private fun signature(contract: String): String {
        val start = contract.indexOf('{')
        if (start < 0) return ""
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (i in start until contract.length) {
            val c = contract[i]
            if (quote != null) {
                if (escaped) escaped = false
                else if (c.code == 92) escaped = true
                else if (c == quote) quote = null
                continue
            }
            if (c.code == 39 || c.code == 34) quote = c
            else if (c == '{') depth++
            else if (c == '}' && --depth == 0) return contract.substring(start + 1, i)
        }
        return ""
    }

    private fun parseFields(signature: String): List<Field> {
        if (signature.isBlank()) return emptyList()
        return splitTopLevel(signature).mapNotNull { raw ->
            val part = raw.trim()
            if (part.isBlank() || part == "...") return@mapNotNull null
            val key = Regex("^([A-Za-z_][A-Za-z0-9_]*)(\\?)?(?:\\s*:\\s*(.*))?$").find(part)
                ?: return@mapNotNull null
            val name = key.groupValues[1]
            val optional = key.groupValues[2] == "?"
            val hint = key.groupValues[3].trim()
            val defaulted = hint.contains('=') || hint.startsWith("optional", true) ||
                hint.startsWith("'") || hint.startsWith("\"")
            Field(name, inferSchema(hint), !optional && !defaulted)
        }.distinctBy { it.name }
    }

    private fun inferSchema(hint: String): JSONObject {
        val value = hint.substringAfter('=', "").trim().trim(',', ' ')
        val literal = value.ifBlank { hint.trim() }
        val lower = hint.lowercase()
        val schema = when {
            lower.startsWith("json") -> JSONObject().put("type", "object")
            lower.startsWith("boolean") || value in setOf("true", "false") ->
                JSONObject().put("type", "boolean")
            lower.startsWith("integer") || value.matches(Regex("-?\\d+")) ->
                JSONObject().put("type", "integer")
            lower.startsWith("number") || value.matches(Regex("-?\\d+\\.\\d+")) ->
                JSONObject().put("type", "number")
            lower.startsWith("array") || hint.startsWith("[") || hint.endsWith("[]") -> {
                val itemHint = when {
                    hint.startsWith("[") -> hint.substring(1).substringBeforeLast("]").trim()
                    hint.endsWith("[]") -> hint.removeSuffix("[]").trim()
                    else -> hint.substringAfter("array", "").trim().removePrefix("of").trim()
                }
                val itemSchema = when {
                    itemHint.startsWith("{") -> objectSchema(parseFields(
                        itemHint.substring(1).substringBeforeLast("}")
                    )).put("additionalProperties", false)
                    itemHint.isBlank() -> JSONObject().put("type", "string")
                    else -> inferSchema(itemHint)
                }
                JSONObject().put("type", "array").put("items", itemSchema)
            }
            lower.startsWith("object") || hint.startsWith("{") -> {
                val inner = if (hint.startsWith("{")) hint.substring(1).substringBeforeLast("}")
                    else hint.substringAfter("object", "").trim().removePrefix("of").trim()
                objectSchema(parseFields(inner))
            }
            else -> JSONObject().put("type", "string")
        }
        val enumMatch = Regex("(?:enum|one of)\\s*\\[([^]]+)]", RegexOption.IGNORE_CASE).find(hint)
        if (enumMatch != null) {
            val values = JSONArray()
            enumMatch.groupValues[1].split('|', ';').map { it.trim().trim { it.code == 39 || it.code == 34 } }
                .filter { it.isNotBlank() }.forEach(values::put)
            if (values.length() > 0) schema.put("enum", values)
        } else if (literal.startsWith("'") || literal.startsWith('"')) {
            val enumValue = literal.trimEnd(',', ' ').trim { it.code == 39 || it.code == 34 }
            if (enumValue.isNotBlank()) schema.put("enum", JSONArray().put(enumValue))
        }
        return schema
    }

    private fun splitTopLevel(value: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var braces = 0
        var brackets = 0
        var parentheses = 0
        var quote: Char? = null
        var escaped = false
        value.forEachIndexed { index, c ->
            if (quote != null) {
                if (escaped) escaped = false
                else if (c.code == 92) escaped = true
                else if (c == quote) quote = null
                return@forEachIndexed
            }
            if (c == '\'' || c == '"') quote = c
            else when (c) {
                '{' -> braces++
                '}' -> braces--
                '[' -> brackets++
                ']' -> brackets--
                '(' -> parentheses++
                ')' -> parentheses--
                ',' -> if (braces == 0 && brackets == 0 && parentheses == 0) {
                    out += value.substring(start, index)
                    start = index + 1
                }
            }
        }
        out += value.substring(start)
        return out
    }
}
