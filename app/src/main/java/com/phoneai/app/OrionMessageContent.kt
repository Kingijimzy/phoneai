package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned, typed conversation payload. The transcript string remains the canonical
 * human-readable fallback, while blocks carry semantic meaning for renderers and storage.
 */
internal data class OrionMessageContent(
    val version: Int = CURRENT_VERSION,
    val blocks: List<OrionMessageBlock>
) {
    init {
        require(version == CURRENT_VERSION) { "Unsupported message content version: $version" }
        require(blocks.isNotEmpty()) { "A message must contain at least one content block." }
    }

    fun toJson(): String = JSONObject().put("version", version).put(
        "blocks", JSONArray().apply { blocks.forEach { put(it.toJsonObject()) } }
    ).toString()

    companion object {
        const val CURRENT_VERSION = 1

        fun fromJson(raw: String): OrionMessageContent {
            val root = JSONObject(raw)
            val version = root.optInt("version", 0)
            require(version == CURRENT_VERSION) { "Unsupported message content version: $version" }
            val array = root.optJSONArray("blocks") ?: JSONArray()
            val blocks = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    parseBlock(item)?.let(::add)
                }
            }
            return OrionMessageContent(version, blocks)
        }

        /** Compatibility adapter. Only formal legacy markers and Markdown fences are parsed. */
        fun fromLegacy(kind: String, raw: String): OrionMessageContent {
            if (kind == "me") return OrionMessageContent(blocks = listOf(OrionMessageBlock.Text(raw)))
            if (kind == "note") {
                when {
                    raw.startsWith("[SHELL:") -> {
                        val shell = OrionShellTranscriptParser.parse(raw)
                        return OrionMessageContent(blocks = listOf(OrionMessageBlock.Terminal(
                            command = shell.command, source = shell.code, stdout = shell.output,
                            stderr = "", exitCode = null,
                            state = when { shell.failed -> "failed"; shell.running -> "running"; else -> "completed" }
                        )))
                    }
                    raw.startsWith("[TASK:APPROVAL]") || raw.startsWith("[APPROVAL:") ->
                        return OrionMessageContent(blocks = listOf(OrionMessageBlock.Approval(
                            approvalId = "", title = "Approval required",
                            details = raw.substringAfter('\n', "").take(4000), risk = "unspecified", state = "pending"
                        )))
                    raw.startsWith("[TASK:") ->
                        return OrionMessageContent(blocks = listOf(OrionMessageBlock.TaskReceipt(
                            taskId = "", objective = raw.substringAfter('\n', "").lineSequence().firstOrNull().orEmpty(),
                            status = raw.substringBefore(']').removePrefix("[TASK:").lowercase(),
                            steps = emptyList(), evidence = raw.take(8000)
                        )))
                    raw.startsWith("[TOOL:") || raw.startsWith("[TOOL_RESULT:") ->
                        return OrionMessageContent(blocks = listOf(OrionMessageBlock.ToolResult(raw)))
                    raw.startsWith("[IMAGE]") ->
                        return OrionMessageContent(blocks = listOf(OrionMessageBlock.Image(raw)))
                }
                return OrionMessageContent(blocks = listOf(OrionMessageBlock.Text(raw)))
            }

            val fence = Regex("(?s)```([A-Za-z0-9_+.-]*)[ \\t]*\\n(.*?)```")
            val blocks = mutableListOf<OrionMessageBlock>()
            var cursor = 0
            fence.findAll(raw).forEach { match ->
                if (match.range.first > cursor) blocks += OrionMessageBlock.Text(raw.substring(cursor, match.range.first))
                blocks += OrionMessageBlock.Code(match.groupValues[1].ifBlank { "text" }, null, match.groupValues[2])
                cursor = match.range.last + 1
            }
            if (cursor < raw.length || blocks.isEmpty()) blocks += OrionMessageBlock.Text(raw.substring(cursor))
            return OrionMessageContent(blocks = blocks)
        }

        private fun parseBlock(json: JSONObject): OrionMessageBlock? = when (json.optString("type")) {
            "text" -> OrionMessageBlock.Text(json.optString("text"))
            "code" -> OrionMessageBlock.Code(json.optString("language", "text"), json.optString("filename").takeIf { it.isNotBlank() }, json.optString("source"))
            "terminal" -> OrionMessageBlock.Terminal(json.optString("command"), json.optString("source"), json.optString("stdout"), json.optString("stderr"), if (json.has("exitCode") && !json.isNull("exitCode")) json.optInt("exitCode") else null, json.optString("state", "unknown"), json.optLong("durationMs").takeIf { it > 0 })
            "generated_file" -> OrionMessageBlock.GeneratedFile(json.optString("artifactId"), json.optString("fileName"), json.optString("mimeType"), json.optLong("sizeBytes"), json.optString("sha256"), json.optString("state", "available"))
            "approval" -> OrionMessageBlock.Approval(json.optString("approvalId"), json.optString("title"), json.optString("details"), json.optString("risk"), json.optString("state", "pending"))
            "task_receipt" -> OrionMessageBlock.TaskReceipt(json.optString("taskId"), json.optString("objective"), json.optString("status"), json.optJSONArray("steps").toStringList(), json.optString("evidence"))
            "thinking" -> OrionMessageBlock.Thinking(json.optString("phase"), json.optString("detail"), json.optLong("timestampMs"))
            "tool_result" -> OrionMessageBlock.ToolResult(json.optString("summary"))
            "research" -> OrionMessageBlock.Research(json.optString("summary"), json.optJSONArray("sources").toStringList())
            "image" -> OrionMessageBlock.Image(json.optString("legacyPayload"))
            else -> null
        }
    }
}

internal sealed interface OrionMessageBlock {
    data class Text(val markdown: String) : OrionMessageBlock
    data class Code(val language: String, val filename: String?, val source: String) : OrionMessageBlock
    data class Terminal(
        val command: String, val source: String, val stdout: String, val stderr: String,
        val exitCode: Int?, val state: String, val durationMs: Long? = null
    ) : OrionMessageBlock
    data class GeneratedFile(
        val artifactId: String, val fileName: String, val mimeType: String,
        val sizeBytes: Long, val sha256: String, val state: String
    ) : OrionMessageBlock
    data class Approval(
        val approvalId: String, val title: String, val details: String, val risk: String, val state: String
    ) : OrionMessageBlock
    data class TaskReceipt(
        val taskId: String, val objective: String, val status: String,
        val steps: List<String>, val evidence: String
    ) : OrionMessageBlock
    data class Thinking(val phase: String, val detail: String, val timestampMs: Long) : OrionMessageBlock
    data class ToolResult(val summary: String) : OrionMessageBlock
    data class Research(val summary: String, val sources: List<String>) : OrionMessageBlock
    data class Image(val legacyPayload: String) : OrionMessageBlock
}

private fun OrionMessageBlock.toJsonObject(): JSONObject = JSONObject().apply {
    when (this@toJsonObject) {
        is OrionMessageBlock.Text -> put("type", "text").put("text", markdown)
        is OrionMessageBlock.Code -> put("type", "code").put("language", language).put("filename", filename).put("source", source)
        is OrionMessageBlock.Terminal -> {
            put("type", "terminal").put("command", command).put("source", source)
                .put("stdout", stdout).put("stderr", stderr).put("state", state)
            if (exitCode != null) put("exitCode", exitCode)
            if (durationMs != null) put("durationMs", durationMs)
        }
        is OrionMessageBlock.GeneratedFile -> put("type", "generated_file").put("artifactId", artifactId)
            .put("fileName", fileName).put("mimeType", mimeType).put("sizeBytes", sizeBytes)
            .put("sha256", sha256).put("state", state)
        is OrionMessageBlock.Approval -> put("type", "approval").put("approvalId", approvalId)
            .put("title", title).put("details", details).put("risk", risk).put("state", state)
        is OrionMessageBlock.TaskReceipt -> put("type", "task_receipt").put("taskId", taskId)
            .put("objective", objective).put("status", status).put("steps", JSONArray(steps))
            .put("evidence", evidence)
        is OrionMessageBlock.Thinking -> put("type", "thinking").put("phase", phase)
            .put("detail", detail).put("timestampMs", timestampMs)
        is OrionMessageBlock.ToolResult -> put("type", "tool_result").put("summary", summary)
        is OrionMessageBlock.Research -> put("type", "research").put("summary", summary).put("sources", JSONArray(sources))
        is OrionMessageBlock.Image -> put("type", "image").put("legacyPayload", legacyPayload)
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList { for (i in 0 until length()) add(optString(i)) }
}
