package com.phoneai.app

/** Chat bubbles cannot draw tables, so turn markdown tables into short bullet lines first. Pure. */
internal object MarkdownTables {
    private fun cells(line: String): List<String> =
        line.trim().trim('|').split('|').map { it.trim() }

    private fun isRow(line: String): Boolean { val t = line.trim(); return t.length > 2 && t.startsWith("|") && t.endsWith("|") }

    private fun isSeparator(line: String): Boolean =
        isRow(line) && cells(line).all { it.isNotEmpty() && it.all { ch -> ch == '-' || ch == ':' || ch == ' ' } }

    fun flatten(text: String): String {
        if (!text.contains('|')) return text
        val lines = text.replace("\r\n", "\n").split('\n')
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (isRow(l) && i + 1 < lines.size && isSeparator(lines[i + 1])) {
                out.add("**" + cells(l).filter { it.isNotEmpty() }.joinToString(" · ") + "**")
                i += 2
                while (i < lines.size && isRow(lines[i])) {
                    val c = cells(lines[i]).filter { it.isNotEmpty() }
                    if (c.isNotEmpty()) out.add("- " + (if (c.size > 1) "**${c[0]}** — " + c.drop(1).joinToString(" · ") else c[0]))
                    i++
                }
            } else if (isRow(l) && !isSeparator(l)) {
                out.add("- " + cells(l).filter { it.isNotEmpty() }.joinToString(" · ")); i++
            } else { out.add(l); i++ }
        }
        return out.joinToString("\n")
    }
}
