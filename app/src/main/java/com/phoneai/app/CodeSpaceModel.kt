package com.phoneai.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import java.io.File

/** Pure logic behind the Code Space screen: safe paths, listings, copying and syntax colouring. */
internal object CodeSpaceModel {
    data class Entry(val name: String, val isDir: Boolean, val size: Long, val modified: Long = 0L)

    const val MAX_EDIT_BYTES = 400_000L
    const val MAX_COPY_BYTES = 200L * 1024 * 1024
    private val hidden = setOf(".git", "node_modules", "__pycache__", ".venvs", ".gradle")

    /** Resolves [rel] under [root]; null when it would escape the root (.., symlink tricks). */
    fun resolve(root: File, rel: String): File? {
        val base = root.canonicalFile
        val target = File(base, rel.trim().trimStart('/')).canonicalFile
        return target.takeIf { it == base || it.path.startsWith(base.path + File.separator) }
    }

    /** Folders first, then files, each sorted case-insensitively; heavy generated folders are left out. */
    fun list(dir: File, limit: Int = 600): List<Entry> =
        (dir.listFiles()?.toList() ?: emptyList())
            .filterNot { it.name in hidden }
            .map { Entry(it.name, it.isDirectory, if (it.isFile) it.length() else 0L, it.lastModified()) }
            .sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
            .take(limit)

    fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var i = 2
        while (File(dir, "$stem ($i)$ext").exists()) i++
        return "$stem ($i)$ext"
    }

    /** Copies a file or folder into [destDir] without overwriting; refuses to copy a folder into itself. */
    fun copyInto(src: File, destDir: File): String {
        if (!src.exists()) return "Nothing to copy."
        if (!destDir.isDirectory) return "Destination is not a folder."
        if (src.isDirectory && (destDir.canonicalPath + File.separator).startsWith(src.canonicalPath + File.separator)) return "Cannot copy a folder into itself."
        val size = if (src.isDirectory) src.walkTopDown().filter { it.isFile }.sumOf { it.length() } else src.length()
        if (size > MAX_COPY_BYTES) return "Too large to copy here (over 200 MB)."
        val target = File(destDir, uniqueName(destDir, src.name))
        return runCatching {
            if (src.isDirectory) src.copyRecursively(target, overwrite = false) else src.copyTo(target, overwrite = false)
            "Copied ${src.name} to ${target.name}"
        }.getOrElse { "Copy failed: ${it.message}" }
    }


    enum class Kind(val label: String, val color: Long) {
        FOLDER("Folder", 0xFFE5B567), PYTHON("Python", 0xFF6AA9E9), JSON("JSON", 0xFFE0C06A), KOTLIN("Kotlin", 0xFFB589E8),
        SCRIPT("Script", 0xFFE8A065), WEB("Web", 0xFFE87C6A), DOC("Text", 0xFFB5BDC6), IMAGE("Image", 0xFF7BD0A0),
        ARCHIVE("Archive", 0xFFD59BC5), CONFIG("Config", 0xFF8FB8C9), OTHER("File", 0xFF9AA0A6)
    }

    fun kindOf(name: String, isDir: Boolean): Kind = if (isDir) Kind.FOLDER else when (name.substringAfterLast('.', "").lowercase()) {
        "py", "pyw" -> Kind.PYTHON
        "json", "jsonl" -> Kind.JSON
        "kt", "kts", "java", "gradle" -> Kind.KOTLIN
        "js", "mjs", "ts", "tsx", "jsx", "sh", "bash", "rb", "go", "rs", "php", "c", "cpp", "h" -> Kind.SCRIPT
        "html", "htm", "css", "xml" -> Kind.WEB
        "md", "txt", "log", "csv" -> Kind.DOC
        "png", "jpg", "jpeg", "webp", "gif", "svg", "bmp" -> Kind.IMAGE
        "zip", "tar", "gz", "tgz", "7z", "apk", "aab", "jar" -> Kind.ARCHIVE
        "yml", "yaml", "toml", "ini", "properties", "env", "conf" -> Kind.CONFIG
        else -> Kind.OTHER
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** "Nov 18, 12:16" within the year, "Nov 18, 2025" otherwise; empty for unknown times. */
    fun formatDate(ms: Long, now: Long = System.currentTimeMillis(), zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        if (ms <= 0L) return ""
        val fmt = { pattern: String -> java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply { timeZone = zone }.format(java.util.Date(ms)) }
        return if (fmt("yyyy") == java.text.SimpleDateFormat("yyyy", java.util.Locale.US).apply { timeZone = zone }.format(java.util.Date(now))) fmt("MMM d, HH:mm") else fmt("MMM d, yyyy")
    }

    /** One tap-able segment per folder from [root] down to [dir]: label to folder. The first segment is the root itself. */
    fun breadcrumbs(root: File, dir: File, rootLabel: String): List<Pair<String, File>> {
        val base = root.canonicalFile
        val here = dir.canonicalFile
        val out = ArrayList<Pair<String, File>>()
        out += rootLabel to base
        if (here != base && here.path.startsWith(base.path + File.separator)) {
            var cur = base
            here.relativeTo(base).path.split(File.separatorChar).filter { it.isNotEmpty() }.forEach { seg -> cur = File(cur, seg); out += seg to cur }
        }
        return out
    }

    fun validNewName(name: String): Boolean = name.isNotBlank() && name.length <= 80 && !name.contains('/') && !name.contains('\\') && name != "." && name != ".."

    fun runCommandFor(rel: String): String? {
        val q = "'" + rel.replace("'", "'\\''") + "'"
        return when (rel.substringAfterLast('.', "").lowercase()) {
            "py" -> "python3 $q"
            "js", "mjs" -> "node $q"
            "sh" -> "sh $q"
            "rb" -> "ruby $q"
            "go" -> "go run $q"
            "php" -> "php $q"
            else -> null
        }
    }

    private val keyword = Color(0xFFC792EA)
    private val string = Color(0xFFC3E88D)
    private val number = Color(0xFFF78C6C)
    private val comment = Color(0xFF6A737D)

    /** Same text, coloured by the Studio tokenizer; the length is unchanged so cursor mapping stays identity. */
    fun annotate(lang: String, text: String): AnnotatedString = buildAnnotatedString {
        append(text)
        OrionStudioEditor.highlight(lang, text).forEach { s ->
            val c = when (s.tok) {
                OrionStudioEditor.Tok.KEYWORD -> keyword
                OrionStudioEditor.Tok.STRING -> string
                OrionStudioEditor.Tok.NUMBER -> number
                OrionStudioEditor.Tok.COMMENT -> comment
            }
            if (s.start in 0 until s.end && s.end <= text.length) addStyle(SpanStyle(color = c), s.start, s.end)
        }
    }
}
