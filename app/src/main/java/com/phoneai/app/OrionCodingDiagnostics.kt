package com.phoneai.app

/** Extracts actionable compiler/test locations from output without executing or rewriting code. */
internal object OrionCodingDiagnostics {
    data class Finding(
        val category: String,
        val file: String?,
        val line: Int?,
        val message: String
    )

    private val pythonTrace = Regex("""(?m)^\s*File "([^"]+)", line (\d+)(?:, in [^\n]+)?\s*$""")
    private val pytest = Regex("""(?m)^([^\n:]+\.py):(\d+):\s*(.*)$""")
    private val gradle = Regex("""(?m)^e:\s*(?:file://)?([^:\n]+):(\d+):(\d+):\s*(error|warning):\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val node = Regex("""(?m)^([^\n:]+\.(?:js|mjs|cjs)):(\d+)\s*$""")

    fun parse(output: String, limit: Int = 12): List<Finding> {
        val lines = output.lines()
        val found = linkedMapOf<String, Finding>()
        fun add(category: String, file: String?, line: Int?, message: String) {
            val safeFile = file?.take(180)
            val safeMessage = message.replace(Regex("\\s+"), " ").trim().take(260)
            val key = "$category|$safeFile|$line|$safeMessage"
            if (safeMessage.isNotBlank()) found.putIfAbsent(key, Finding(category, safeFile, line, safeMessage))
        }
        pythonTrace.findAll(output).forEach { match ->
            val index = lines.indexOfFirst { it.contains(match.value.trim()) }
            val detail = lines.drop((index + 1).coerceAtLeast(0)).firstOrNull {
                it.trim().isNotBlank() && !it.trim().startsWith("File ")
            }.orEmpty()
            add("python_traceback", match.groupValues[1], match.groupValues[2].toIntOrNull(), detail)
        }
        pytest.findAll(output).forEach { match ->
            add("pytest", match.groupValues[1], match.groupValues[2].toIntOrNull(), match.groupValues[3].trim())
        }
        gradle.findAll(output).forEach { match ->
            add("kotlin_compiler", match.groupValues[1], match.groupValues[2].toIntOrNull(), match.groupValues[5])
        }
        node.findAll(output).forEach { match ->
            add("javascript", match.groupValues[1], match.groupValues[2].toIntOrNull(), lines.firstOrNull { it.contains("SyntaxError") || it.contains("Error:") }.orEmpty())
        }
        if (found.isEmpty()) {
            lines.filter { line ->
                val text = line.trim()
                text.contains("error", true) || text.contains("failed", true) || text.contains("assertion", true)
            }.take(limit).forEach { add("test_output", null, null, it) }
        }
        return found.values.take(limit)
    }

    fun render(findings: List<Finding>): String = if (findings.isEmpty()) {
        "No structured file/line diagnostic could be extracted; inspect the raw test output before editing."
    } else findings.joinToString("\n") { finding ->
        "- [${finding.category}] ${finding.file ?: "file_unknown"}${finding.line?.let { ":$it" }.orEmpty()}: ${finding.message}"
    }
}
