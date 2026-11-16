package com.phoneai.app

/** Pure helpers behind the Code Studio editor: language detection, syntax highlighting, diagnostic parsing. */
internal object OrionStudioEditor {
    enum class Tok { COMMENT, STRING, NUMBER, KEYWORD }
    data class Span(val start: Int, val end: Int, val tok: Tok)
    data class Diag(val file: String, val line: Int, val col: Int, val severity: String, val message: String)

    fun langOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "py" -> "python"; "js", "mjs", "cjs", "jsx" -> "javascript"; "ts", "tsx" -> "typescript"
        "go" -> "go"; "rs" -> "rust"; "c", "h" -> "c"; "cpp", "cc", "hpp" -> "cpp"; "java" -> "java"; "kt" -> "kotlin"
        "sh", "bash" -> "shell"; "rb" -> "ruby"; "php" -> "php"; "json" -> "json"; "html", "htm" -> "html"; "css" -> "css"
        "md" -> "markdown"; "yml", "yaml" -> "yaml"; "sql" -> "sql"; else -> "text"
    }

    private val keywords: Map<String, String> = mapOf(
        "python" to "def class return if elif else for while import from as with try except finally raise pass break continue lambda yield async await in is not and or None True False global nonlocal assert del",
        "javascript" to "function const let var return if else for while do switch case break continue new class extends import from export default async await try catch finally throw typeof instanceof null undefined true false this of in",
        "typescript" to "function const let var return if else for while do switch case break continue new class extends import from export default async await try catch finally throw typeof instanceof null undefined true false this of in interface type enum implements public private protected readonly",
        "go" to "func package import return if else for range switch case default break continue go defer chan select struct interface map type var const nil true false",
        "rust" to "fn let mut pub struct enum impl trait use mod match if else for while loop return break continue self Self true false const static async await move ref where",
        "c" to "int char float double void long short unsigned signed struct union enum typedef static const extern return if else for while do switch case break continue sizeof NULL include define",
        "cpp" to "int char float double void long short unsigned signed struct union enum typedef static const extern return if else for while do switch case break continue sizeof class public private protected template typename namespace using new delete nullptr true false include define",
        "java" to "public private protected class interface extends implements static final void int long double boolean new return if else for while do switch case break continue try catch finally throw throws import package null true false this super",
        "kotlin" to "fun val var class object interface return if else for while when in is as import package null true false this super override open data sealed suspend",
        "shell" to "if then else elif fi for while do done case esac function in echo export local return exit cd source",
        "ruby" to "def end class module if elsif else unless while for do return yield require include nil true false self begin rescue ensure",
        "php" to "function class public private protected return if else elseif foreach for while echo use namespace new null true false this",
        "sql" to "select from where insert into values update set delete create table drop alter join left right inner on group by order having limit and or not null as distinct union",
        "json" to "true false null", "yaml" to "true false null"
    )
    private val hashComment = setOf("python", "shell", "ruby", "yaml")

    fun highlight(lang: String, text: String): List<Span> {
        if (text.isEmpty() || text.length > 200_000) return emptyList()
        val comment = when {
            lang in hashComment -> "#[^\\n]*"
            lang == "sql" -> "--[^\\n]*"
            lang == "html" -> "<!--[\\s\\S]*?-->"
            lang in setOf("text", "markdown", "json") -> null
            else -> "//[^\\n]*|/\\*[\\s\\S]*?\\*/"
        }
        val kw = keywords[lang]?.split(' ')?.joinToString("|") { Regex.escape(it) }
        val pattern = (comment?.let { "($it)" } ?: "(?!x)x") + "|(\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*')|(\\b\\d+(?:\\.\\d+)?\\b)|" +
            (kw?.let { "(\\b(?:$it)\\b)" } ?: "(?!x)x")
        val out = ArrayList<Span>()
        for (m in Regex(pattern, if (lang == "sql") setOf(RegexOption.IGNORE_CASE) else emptySet()).findAll(text)) {
            val tok = when {
                m.groups[1] != null -> Tok.COMMENT
                m.groups[2] != null -> Tok.STRING
                m.groups[3] != null -> Tok.NUMBER
                m.groups[4] != null -> Tok.KEYWORD
                else -> continue
            }
            out += Span(m.range.first, m.range.last + 1, tok)
        }
        return out
    }

    private val gccStyle = Regex("^(?:/workspace/)?([^\\s:()]+\\.[A-Za-z0-9]+):(\\d+)(?::(\\d+))?:?\\s*(?:-\\s*)?(error|warning|note|info|[A-Z]\\d{2,4})?[: ]*(.*)$")
    private val pyrightStyle = Regex("^\\s*(?:/workspace/)?([^\\s:()]+\\.[A-Za-z0-9]+):(\\d+):(\\d+)\\s+-\\s+(error|warning|information)[: ]*(.*)$")
    private val tscStyle = Regex("^(?:/workspace/)?([^\\s:()]+\\.[A-Za-z0-9]+)\\((\\d+),(\\d+)\\):\\s*(error|warning)\\s*(.*)$")

    /** Understands ruff/gcc/go vet/shellcheck/pyright/tsc/node --check style lines. */
    fun parseDiagnostics(output: String): List<Diag> {
        val diags = ArrayList<Diag>()
        for (raw in output.lineSequence()) {
            val line = raw.trimEnd()
            val m = pyrightStyle.matchEntire(line) ?: tscStyle.matchEntire(line) ?: gccStyle.matchEntire(line) ?: continue
            val g = m.groupValues
            val msg = g[5].trim()
            if (msg.isEmpty() && g[4].isEmpty()) continue
            val sev = when {
                g[4].equals("warning", true) || g[4].startsWith("W") -> "warning"
                g[4].equals("note", true) || g[4].equals("info", true) || g[4].equals("information", true) -> "info"
                else -> "error"
            }
            diags += Diag(g[1], g[2].toIntOrNull() ?: continue, g[3].toIntOrNull() ?: 1, sev, if (g[4].matches(Regex("[A-Z]\\d{2,4}"))) g[4] + " " + msg else msg)
        }
        return diags.distinct().take(200)
    }

    /** Character offset of (1-based line, 1-based col) in text, clamped. */
    fun offsetOf(text: String, line: Int, col: Int): Int {
        var idx = 0
        var l = 1
        while (l < line) {
            val nl = text.indexOf('\n', idx)
            if (nl < 0) return text.length
            idx = nl + 1; l++
        }
        return (idx + (col - 1).coerceAtLeast(0)).coerceAtMost(text.length)
    }

    /** (1-based line, 0-based col) of a cursor offset. */
    fun position(text: String, offset: Int): Pair<Int, Int> {
        val o = offset.coerceIn(0, text.length)
        val before = text.substring(0, o)
        return (before.count { it == '\n' } + 1) to (o - (before.lastIndexOf('\n') + 1))
    }

    /** The identifier prefix immediately before the cursor, used to turn a completion into an insertion. */
    fun wordBefore(text: String, offset: Int): String {
        var i = offset.coerceIn(0, text.length)
        val end = i
        while (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '_')) i--
        return text.substring(i, end)
    }
}
