package com.phoneai.app

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Bounded text extraction for DOCX, XLSX and PPTX (zip + XML). Pure JVM so it is unit-testable.
 * Never executes anything: it only reads text nodes from known XML parts, with size caps against zip bombs.
 */
object OrionOfficeExtractor {
    private const val MAX_PART_BYTES = 2 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
    val supported = setOf("docx", "xlsx", "pptx")

    fun extract(ext: String, bytes: ByteArray, maxChars: Int = SafeDocumentExtractor.MAX_CHARS): String {
        require(ext in supported) { "Unsupported Office type: .$ext" }
        val parts = readParts(bytes, ext)
        val text = when (ext) {
            "docx" -> parts.filterKeys { it == "word/document.xml" }.values.joinToString("\n") { paragraphs(it, "w:p", "w:t") }
            "pptx" -> parts.filterKeys { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                .toSortedMap(compareBy({ slideNo(it) }, { it }))
                .entries.joinToString("\n\n") { "Slide ${slideNo(it.key)}:\n" + paragraphs(it.value, "a:p", "a:t") }
            else -> sheets(parts)
        }
        return text.replace(Regex(" +"), " ").trim().take(maxChars)
    }

    private fun slideNo(name: String): Int = Regex("(\\d+)").find(name.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0

    private fun readParts(bytes: ByteArray, ext: String): Map<String, String> {
        val wanted: (String) -> Boolean = when (ext) {
            "docx" -> { n -> n == "word/document.xml" }
            "pptx" -> { n -> n.startsWith("ppt/slides/slide") && n.endsWith(".xml") }
            else -> { n -> n == "xl/sharedStrings.xml" || (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml")) }
        }
        val out = linkedMapOf<String, String>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !wanted(entry.name)) continue
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    if (buf.size() + n > MAX_PART_BYTES || total + n > MAX_TOTAL_BYTES) throw IllegalArgumentException("Document is too large to read safely.")
                    buf.write(chunk, 0, n); total += n
                }
                out[entry.name] = buf.toString(Charsets.UTF_8.name())
            }
        }
        if (out.isEmpty()) throw IllegalArgumentException("This is not a valid .$ext file.")
        return out
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun paragraphs(xml: String, para: String, run: String): String {
        val p = Regex("<$para[ >].*?</$para>", RegexOption.DOT_MATCHES_ALL)
        val t = Regex("<$run(?: [^>]*)?>(.*?)</$run>", RegexOption.DOT_MATCHES_ALL)
        return p.findAll(xml).map { m -> t.findAll(m.value).joinToString("") { unescape(it.groupValues[1]) } }
            .filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun sheets(parts: Map<String, String>): String {
        val shared = parts["xl/sharedStrings.xml"]?.let { xml ->
            Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { si ->
                Regex("<t(?: [^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) }
            }.toList()
        } ?: emptyList()
        val sb = StringBuilder()
        parts.filterKeys { it.startsWith("xl/worksheets/") }.toSortedMap().entries.forEachIndexed { i, (_, xml) ->
            if (i > 0) sb.append("\n\n")
            sb.append("Sheet ").append(i + 1).append(":\n")
            Regex("<row[ >].*?</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { row ->
                val cells = Regex("<c ([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL).findAll(row.value).map { c ->
                    val attrs = c.groupValues[1]
                    val body = c.groupValues[2]
                    val v = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                    val inline = Regex("<t(?: [^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                    when {
                        attrs.contains("t=\"s\"") -> v?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                        inline != null -> unescape(inline)
                        else -> v ?: ""
                    }
                }.toList()
                if (cells.any { it.isNotBlank() }) sb.append(cells.joinToString("\t")).append('\n')
            }
        }
        return sb.toString()
    }
}
