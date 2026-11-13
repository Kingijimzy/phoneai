package com.phoneai.app

/**
 * Parsed, presentation-safe view of a legacy shell activity transcript.
 *
 * The runtime currently persists shell notes as text with invisible CODE/OUT separators.
 * Keep that wire format at the boundary and give the UI a typed value; do not make each
 * renderer independently guess where source code and stdout begin.
 */
internal data class OrionShellTranscript(
    val phase: String,
    val command: String,
    val code: String,
    val output: String
) {
    val failed: Boolean get() = phase in setOf("FAILED", "ERROR", "DENIED")
    val running: Boolean get() = phase in setOf("RUNNING", "STARTED", "ACTIVE", "IN_PROGRESS")
}

internal object OrionShellTranscriptParser {
    private const val CODE_MARKER = "\u2063CODE\n"
    private const val OUTPUT_MARKER = "\u2063OUT\n"

    fun parse(raw: String): OrionShellTranscript {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val header = normalized.substringBefore('\n')
        val phase = Regex("^\\[SHELL(?::([A-Z_]+))?]", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.getOrNull(1)?.uppercase().orEmpty().ifBlank { "UPDATE" }
        val body = normalized.substringAfter('\n', "")
        val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        val command = firstLine.removePrefix("\$ ").trim()

        val codeAt = body.indexOf(CODE_MARKER)
        val outputAt = body.indexOf(OUTPUT_MARKER)
        if (codeAt >= 0 && outputAt > codeAt) {
            val code = body.substring(codeAt + CODE_MARKER.length, outputAt).trimEnd()
            val output = body.substring(outputAt + OUTPUT_MARKER.length).trim()
            return OrionShellTranscript(phase, command, code, output)
        }

        // Some legacy surfaces replace the invisible separator with a newline before
        // rendering. Accept that form as well so restored notes remain readable.
        val lines = body.replace("\u2063", "\n").lines()
        val codeLine = lines.indexOfFirst { it.trim() == "CODE" }
        val outputLine = lines.indexOfFirst { it.trim() == "OUT" }
        if (codeLine >= 0 && outputLine > codeLine) {
            val code = lines.subList(codeLine + 1, outputLine).joinToString("\n").trimEnd()
            val output = lines.drop(outputLine + 1).joinToString("\n").trim()
            return OrionShellTranscript(phase, command, code, output)
        }
        if (outputLine >= 0) {
            val output = lines.drop(outputLine + 1).joinToString("\n").trim()
            return OrionShellTranscript(phase, command, "", output)
        }

        val output = lines.dropWhile { it.isBlank() || it.trim() == firstLine }
            .joinToString("\n").trim()
        return OrionShellTranscript(phase, command, "", output)
    }
}
