package com.phoneai.app

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/** Professional editor: syntax highlighting, error squiggles, hover, autocomplete, diagnostics, file tree. */
internal class StudioEditorPage(host: StudioActivity) : StudioPage(host) {
    override val id = "editor"
    override val title = "Editor"

    private class HlSpan(color: Int) : ForegroundColorSpan(color)
    private class ErrSpan(color: Int) : BackgroundColorSpan(color)
    private class UlSpan : UnderlineSpan()

    private lateinit var fileField: EditText
    private lateinit var editor: EditText
    private lateinit var diagBox: LinearLayout
    private lateinit var completeRow: LinearLayout
    private lateinit var info: TextView
    private lateinit var tree: LinearLayout
    private val ui2 = Handler(Looper.getMainLooper())
    private val rehighlight = Runnable { highlight() }
    private var diags: List<OrionStudioEditor.Diag> = emptyList()

    fun currentFile(): String? = fileField.text.toString().trim().takeIf { it.isNotEmpty() }

    override fun build(root: LinearLayout) {
        root.addView(ui.section("Editor"))
        fileField = ui.field("file, e.g. app.py")
        root.addView(fileField)
        root.addView(ui.row(
            ui.chip("Files") { toggleTree() }, ui.chip("Save") { save() }, ui.chip("Check") { check() }, ui.chip("Info") { hover() },
            ui.chip("Complete") { complete() }, ui.chip("Format") { host.runAction("format") { info.text = it.take(3000) } }, ui.chip("Run") { runFile() }
        ))
        tree = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE; background = ui.panel(); setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)) }
        root.addView(tree)
        editor = EditText(host).apply {
            typeface = Typeface.MONOSPACE; textSize = 12.5f; setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED)
            hint = "Open a file from Files, or start typing…"; gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); minHeight = ui.dp(300); background = ui.panel()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { ui2.removeCallbacks(rehighlight); ui2.postDelayed(rehighlight, 300) }
            })
        }
        root.addView(editor)
        info = ui.output(60); root.addView(info)
        diagBox = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        root.addView(diagBox)
        completeRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(host).apply { isHorizontalScrollBarEnabled = false; addView(completeRow) })
    }

    // ----- open / save -----
    private fun workspaceFile(rel: String): File? {
        if (!OrionStudio.validPath(rel)) return null
        val root = ShellRunner.workspace(host).canonicalFile
        val f = File(root, rel).canonicalFile
        return if (f.path.startsWith(root.path + File.separator)) f else null
    }
    private fun relPath(): String = host.project.trim('/').let { if (it.isEmpty()) "" else "$it/" } + fileField.text.toString().trim().trim('/')

    fun openFile(rel: String) {
        val dir = rel.substringBeforeLast('/', "")
        host.setProject(dir)
        fileField.setText(rel.substringAfterLast('/'))
        host.bg {
            val f = workspaceFile(rel)
            val text = if (f != null && f.isFile && f.length() < 400_000) runCatching { f.readText() }.getOrNull() else null
            host.post { if (text == null) info.text = "Could not open /workspace/$rel" else { editor.setText(text); highlight(); info.text = "Opened /workspace/$rel" } }
        }
    }

    fun openSnippet(code: String, lang: String) {
        val ext = mapOf("python" to "py", "javascript" to "js", "typescript" to "ts", "shell" to "sh", "bash" to "sh", "kotlin" to "kt", "markdown" to "md", "text" to "txt").getOrDefault(lang, lang.ifBlank { "txt" })
        host.setProject("snippets"); fileField.setText("snippet.$ext"); editor.setText(code); highlight()
    }

    private fun toggleTree() {
        if (tree.visibility == android.view.View.VISIBLE) { tree.visibility = android.view.View.GONE; return }
        host.bg {
            val root = ShellRunner.workspace(host).canonicalFile
            val files = root.walkTopDown().maxDepth(4)
                .onEnter { d -> d == root || !(d.name.startsWith(".") || d.name == "node_modules" || d.name == "__pycache__") }
                .filter { it.isFile && it.length() < 400_000 }.map { it.relativeTo(root).path }.sorted().take(300).toList()
            host.post {
                tree.removeAllViews()
                if (files.isEmpty()) tree.addView(ui.label("The workspace is empty. Name a file and Save, or ask Orion to build something.", 12f, Theme.MUTED))
                files.forEach { f -> tree.addView(ui.label(f, 13f, Theme.TEXT).apply { setPadding(0, ui.dp(7), 0, ui.dp(7)); setOnClickListener { openFile(f); tree.visibility = android.view.View.GONE } }) }
                tree.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun save() {
        val rel = relPath(); val f = workspaceFile(rel)
        if (f == null || fileField.text.isBlank()) { info.text = "Type a valid relative file name first."; return }
        val text = editor.text.toString()
        host.bg {
            val ok = runCatching { f.parentFile?.mkdirs(); f.writeText(text); true }.getOrDefault(false)
            host.post { info.text = if (ok) "Saved /workspace/$rel" else "Could not save /workspace/$rel" }
        }
    }

    private fun persistForTools(rel: String, text: String) { workspaceFile(rel)?.let { f -> runCatching { f.parentFile?.mkdirs(); f.writeText(text) } } }

    // ----- highlighting and squiggles -----
    private fun highlight() {
        val e = editor.text ?: return
        e.getSpans(0, e.length, HlSpan::class.java).forEach { e.removeSpan(it) }
        val lang = OrionStudioEditor.langOf(fileField.text.toString())
        val colors = mapOf(OrionStudioEditor.Tok.KEYWORD to 0xFFC9D2FF.toInt(), OrionStudioEditor.Tok.STRING to 0xFF9FD3A8.toInt(),
            OrionStudioEditor.Tok.NUMBER to 0xFFE0B97A.toInt(), OrionStudioEditor.Tok.COMMENT to 0xFF7C8594.toInt())
        OrionStudioEditor.highlight(lang, e.toString()).forEach { sp -> e.setSpan(HlSpan(colors.getValue(sp.tok)), sp.start, sp.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    private fun squiggle() {
        val e = editor.text ?: return
        e.getSpans(0, e.length, ErrSpan::class.java).forEach { e.removeSpan(it) }
        e.getSpans(0, e.length, UlSpan::class.java).forEach { e.removeSpan(it) }
        val text = e.toString()
        val mine = fileField.text.toString().trim().substringAfterLast('/')
        diags.filter { it.file.substringAfterLast('/') == mine }.forEach { d ->
            val start = OrionStudioEditor.offsetOf(text, d.line, d.col)
            var end = start
            while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
            if (end == start) end = (start + 1).coerceAtMost(text.length)
            if (start < end) {
                e.setSpan(ErrSpan(if (d.severity == "error") 0x55E05A5A else 0x55E0B94A), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                e.setSpan(UlSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    // ----- language intelligence -----
    private fun check() {
        val rel = relPath(); if (fileField.text.isBlank()) { info.text = "Open or name a file first."; return }
        val text = editor.text.toString()
        info.text = "Checking $rel…"
        host.bg {
            persistForTools(rel, text)
            val out = OrionStudio.run(host, JSONObject().put("action", "file_diagnostics").put("file", rel))
            val parsed = OrionStudioEditor.parseDiagnostics(out)
            host.post {
                diags = parsed; squiggle(); diagBox.removeAllViews()
                if (parsed.isEmpty()) info.text = if (out.startsWith("error")) out else "No problems found.\n" + out.take(1200)
                else {
                    info.text = "${parsed.size} problem${if (parsed.size == 1) "" else "s"} — tap one to jump to it."
                    parsed.take(30).forEach { d ->
                        diagBox.addView(ui.label("${if (d.severity == "error") "✖" else "▲"} ${d.line}:${d.col}  ${d.message}", 12f, if (d.severity == "error") 0xFFE88A8A.toInt() else 0xFFE0B97A.toInt()).apply {
                            setOnClickListener {
                                val off = OrionStudioEditor.offsetOf(editor.text.toString(), d.line, d.col)
                                editor.requestFocus(); editor.setSelection(off.coerceIn(0, editor.text.length))
                            }
                        })
                    }
                }
            }
        }
    }

    private fun hover() {
        val rel = relPath(); val text = editor.text.toString()
        val (line, col) = OrionStudioEditor.position(text, editor.selectionStart)
        info.text = "Looking up the symbol…"
        host.bg {
            persistForTools(rel, text)
            val r = OrionStudio.run(host, JSONObject().put("action", "hover").put("file", rel).put("line", line).put("col", col))
            host.post { info.text = r.take(3000) }
        }
    }

    private fun complete() {
        val rel = relPath(); val text = editor.text.toString()
        val (line, col) = OrionStudioEditor.position(text, editor.selectionStart)
        info.text = "Completing…"
        host.bg {
            persistForTools(rel, text)
            val out = OrionStudio.run(host, JSONObject().put("action", "complete").put("file", rel).put("line", line).put("col", col))
            val names = out.lines().mapNotNull { it.substringBefore(" - ", "").trim().takeIf { n -> n.isNotEmpty() && !n.contains(' ') } }.distinct().take(25)
            host.post {
                completeRow.removeAllViews()
                if (names.isEmpty()) info.text = if (out.startsWith("error")) out else "No suggestions here."
                else {
                    info.text = "Tap a suggestion to insert it."
                    names.forEach { n ->
                        completeRow.addView(ui.chip(n) {
                            val pos = editor.selectionStart
                            val prefix = OrionStudioEditor.wordBefore(editor.text.toString(), pos)
                            editor.text.insert(pos, if (n.startsWith(prefix)) n.removePrefix(prefix) else n)
                            completeRow.removeAllViews()
                        })
                    }
                }
            }
        }
    }

    private fun runFile() {
        val rel = relPath(); if (fileField.text.isBlank()) { info.text = "Open or name a file first."; return }
        val text = editor.text.toString()
        val cmd = when (OrionStudioEditor.langOf(rel)) {
            "python" -> "python3 /workspace/$rel"; "javascript" -> "node /workspace/$rel"; "shell" -> "sh /workspace/$rel"
            "go" -> "go run /workspace/$rel"; "ruby" -> "ruby /workspace/$rel"; "php" -> "php /workspace/$rel"
            else -> { info.text = "No runner for this file type."; return }
        }
        info.text = "Running…"
        host.bg {
            persistForTools(rel, text)
            val r = OrionStudio.run(host, JSONObject().put("action", "terminal").put("command", "$cmd 2>&1 | tail -n 80").put("project", ""))
            host.post { info.text = r.take(6000) }
        }
    }
}
