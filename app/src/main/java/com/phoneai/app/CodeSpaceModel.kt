package com.phoneai.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import java.io.File

/** Pure logic behind the Code Space screen: safe paths, listings, copying and syntax colouring. */
internal object CodeSpaceModel {
    data class Entry(val name: String, val isDir: Boolean, val size: Long)

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
            .map { Entry(it.name, it.isDirectory, if (it.isFile) it.length() else 0L) }
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
