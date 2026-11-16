package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File

/**
 * Code Studio: a live editor (syntax highlighting, error squiggles, hover, completion) plus Git/build/preview,
 * runtime status and the shared plugin/app/extension/MCP registry. Chat cards open straight into it.
 */
class StudioActivity : Activity() {
    private class HlSpan(color: Int) : ForegroundColorSpan(color)
    private class ErrSpan(color: Int) : BackgroundColorSpan(color)
    private class UlSpan : UnderlineSpan()

    private lateinit var output: TextView
    private lateinit var project: EditText
    private lateinit var fileField: EditText
    private lateinit var editor: EditText
    private lateinit var diagBox: LinearLayout
    private lateinit var completeRow: LinearLayout
    private lateinit var registry: LinearLayout
    private lateinit var searchField: EditText
    private val ui = Handler(Looper.getMainLooper())
    private var autoStatus = false
    private var diags: List<OrionStudioEditor.Diag> = emptyList()
    private val rehighlight = Runnable { highlight() }
    private val live = object : Runnable { override fun run() { if (autoStatus) refreshStatus(); ui.postDelayed(this, 4000) } }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(v: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = v; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, dp(4))
    }
    private fun chip(caption: String, action: () -> Unit) = TextView(this).apply {
        text = caption; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Theme.TEXT); gravity = Gravity.CENTER
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = GradientDrawable().apply { setColor(Theme.PANEL2); cornerRadius = dp(11).toFloat(); setStroke(dp(1), Theme.LINE_STRONG) }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
        isClickable = true; setOnClickListener { action() }
    }
    private fun row(vararg chips: TextView) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; chips.forEach { addView(it) } })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }
    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint; setHintTextColor(Theme.MUTED); setTextColor(Theme.TEXT); textSize = 14f; setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    private fun panel() = GradientDrawable().apply { setColor(Theme.PANEL); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Theme.LINE) }

    private fun workspaceFile(rel: String): File? {
        if (!OrionStudio.validPath(rel)) return null
        val root = ShellRunner.workspace(this).canonicalFile
        val f = File(root, rel).canonicalFile
        return if (f.path.startsWith(root.path + File.separator)) f else null
    }
    private fun relPath(): String = (project.text.toString().trim().trim('/').let { if (it.isEmpty()) "" else "$it/" }) + fileField.text.toString().trim().trim('/')

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG; window.navigationBarColor = Theme.BG
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val scroll = ScrollView(this).apply { setBackgroundColor(Theme.BG) }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(40), dp(18), dp(30)) }
        scroll.addView(page); setContentView(scroll)
        page.addView(label("ORION / CODE STUDIO", 10f, Theme.MUTED, true))
        page.addView(label("Build anything", 26f, Theme.TEXT, true))
        page.addView(label("Everything here is also available to Orion from chat. Cards in chat open straight into this editor.", 12f, Theme.MUTED))

        project = field("project folder under /workspace (blank = whole workspace)")
        fileField = field("file, e.g. app.py")
        page.addView(project); page.addView(fileField)
        page.addView(row(chip("Open…") { pickFile() }, chip("Save") { save() }, chip("Check") { check() }, chip("Info at cursor") { hover() }, chip("Complete") { complete() }, chip("Run file") { runFile() }))

        editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12.5f; setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED)
            hint = "Open a file, or start typing…"; gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setPadding(dp(12), dp(12), dp(12), dp(12)); minHeight = dp(260); background = panel()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { ui.removeCallbacks(rehighlight); ui.postDelayed(rehighlight, 300) }
            })
        }
        page.addView(editor)
        diagBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(diagBox)
        completeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        page.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(completeRow) })

        page.addView(row(chip("Status") { autoStatus = true; refreshStatus() }, chip("Toolchains") { go("toolchain_list") }, chip("Diagnostics") { go("diagnostics") }, chip("Format") { go("format") }))
        page.addView(row(chip("Git status") { go("git", "op" to "status") }, chip("Diff") { go("git", "op" to "diff") }, chip("Log") { go("git", "op" to "log") }, chip("Commit") { go("git", "op" to "commit", "message" to "Studio commit") }))
        page.addView(row(chip("Build") { go("build") }, chip("Test") { go("test") }, chip("Preview") { go("preview") }, chip("Ubuntu") { go("ubuntu_install") }))
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11.5f; setTextColor(Theme.TEXT); setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12)); minHeight = dp(140); background = panel()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        page.addView(output)

        page.addView(label("Registry — plugins, apps, extensions, MCP", 15f, Theme.TEXT, true).apply { setPadding(0, dp(18), 0, dp(4)) })
        searchField = field("search Open VSX / npm / MCP registry")
        page.addView(searchField)
        page.addView(row(chip("VS Code ext") { discover("openvsx") }, chip("npm") { discover("npm") }, chip("MCP servers") { discover("mcp") }))
        registry = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(registry)
        renderRegistry()

        intent.getStringExtra("file")?.let { loadFile(it) }
        intent.getStringExtra("snippet")?.let { code ->
            val lang = intent.getStringExtra("lang").orEmpty()
            val ext = mapOf("python" to "py", "javascript" to "js", "typescript" to "ts", "shell" to "sh", "bash" to "sh", "kotlin" to "kt", "markdown" to "md", "text" to "txt").getOrDefault(lang, lang.ifBlank { "txt" })
            project.setText("snippets"); fileField.setText("snippet.$ext"); editor.setText(code); highlight()
        }
    }

    override fun onResume() { super.onResume(); ui.post(live) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(live) }

    private fun show(text: String) = runOnUiThread { output.text = text.take(12000) }

    // ----- editor -----
    private fun loadFile(rel: String) {
        val dir = rel.substringBeforeLast('/', "")
        val name = rel.substringAfterLast('/')
        project.setText(dir); fileField.setText(name)
        Thread {
            val f = workspaceFile(rel)
            val text = if (f != null && f.isFile && f.length() < 400_000) runCatching { f.readText() }.getOrNull() else null
            runOnUiThread { if (text == null) show("Could not open /workspace/$rel") else { editor.setText(text); highlight(); show("Opened /workspace/$rel") } }
        }.start()
    }

    private fun pickFile() {
        Thread {
            val root = ShellRunner.workspace(this).canonicalFile
            val files = root.walkTopDown().maxDepth(4)
                .onEnter { d -> d == root || !(d.name.startsWith(".") || d.name == "node_modules" || d.name == "__pycache__") }
                .filter { it.isFile && it.length() < 400_000 }.map { it.relativeTo(root).path }.sorted().take(300).toList()
            runOnUiThread {
                if (files.isEmpty()) show("The workspace has no files yet. Type a name and Save, or ask Orion to build something.")
                else AlertDialog.Builder(this).setTitle("Open file").setItems(files.toTypedArray()) { _, i -> loadFile(files[i]) }.show()
            }
        }.start()
    }

    private fun save() {
        val rel = relPath(); val f = workspaceFile(rel)
        if (f == null || fileField.text.isBlank()) { show("Type a valid relative file name first."); return }
        val text = editor.text.toString()
        Thread {
            val ok = runCatching { f.parentFile?.mkdirs(); f.writeText(text); true }.getOrDefault(false)
            show(if (ok) "Saved /workspace/$rel" else "Could not save /workspace/$rel")
        }.start()
    }

    private fun highlight() {
        val e = editor.text ?: return
        e.getSpans(0, e.length, HlSpan::class.java).forEach { e.removeSpan(it) }
        val lang = OrionStudioEditor.langOf(fileField.text.toString())
        val colors = mapOf(OrionStudioEditor.Tok.KEYWORD to 0xFFC9D2FF.toInt(), OrionStudioEditor.Tok.STRING to 0xFF9FD3A8.toInt(),
            OrionStudioEditor.Tok.NUMBER to 0xFFE0B97A.toInt(), OrionStudioEditor.Tok.COMMENT to 0xFF7C8594.toInt())
        OrionStudioEditor.highlight(lang, e.toString()).forEach { sp ->
            e.setSpan(HlSpan(colors.getValue(sp.tok)), sp.start, sp.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
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

    private fun check() {
        val rel = relPath(); if (fileField.text.isBlank()) { show("Open or name a file first."); return }
        val text = editor.text.toString()
        show("Checking $rel…")
        Thread {
            workspaceFile(rel)?.let { f -> runCatching { f.parentFile?.mkdirs(); f.writeText(text) } }
            val out = OrionStudio.run(applicationContext, JSONObject().put("action", "file_diagnostics").put("file", rel))
            val parsed = OrionStudioEditor.parseDiagnostics(out)
            runOnUiThread {
                diags = parsed; squiggle(); diagBox.removeAllViews()
                if (parsed.isEmpty()) show(if (out.startsWith("error")) out else "No problems found.\n\n" + out.take(1500))
                else {
                    show("${parsed.size} problem${if (parsed.size == 1) "" else "s"} — tap one to jump to it.")
                    parsed.take(30).forEach { d ->
                        diagBox.addView(label("${if (d.severity == "error") "✖" else "▲"} ${d.line}:${d.col}  ${d.message}", 12f, if (d.severity == "error") 0xFFE88A8A.toInt() else 0xFFE0B97A.toInt()).apply {
                            setOnClickListener {
                                val off = OrionStudioEditor.offsetOf(editor.text.toString(), d.line, d.col)
                                editor.requestFocus(); editor.setSelection(off.coerceIn(0, editor.text.length))
                            }
                        })
                    }
                }
            }
        }.start()
    }

    private fun hover() {
        val rel = relPath(); val (line, col) = OrionStudioEditor.position(editor.text.toString(), editor.selectionStart)
        val text = editor.text.toString()
        show("Looking up the symbol…")
        Thread {
            workspaceFile(rel)?.let { f -> runCatching { f.writeText(text) } }
            show(OrionStudio.run(applicationContext, JSONObject().put("action", "hover").put("file", rel).put("line", line).put("col", col)))
        }.start()
    }

    private fun complete() {
        val rel = relPath(); val text = editor.text.toString()
        val (line, col) = OrionStudioEditor.position(text, editor.selectionStart)
        show("Completing…")
        Thread {
            workspaceFile(rel)?.let { f -> runCatching { f.writeText(text) } }
            val out = OrionStudio.run(applicationContext, JSONObject().put("action", "complete").put("file", rel).put("line", line).put("col", col))
            val names = out.lines().mapNotNull { it.substringBefore(" - ", "").trim().takeIf { n -> n.isNotEmpty() && !n.contains(' ') } }.distinct().take(25)
            runOnUiThread {
                completeRow.removeAllViews()
                if (names.isEmpty()) show(if (out.startsWith("error")) out else "No suggestions here.")
                else {
                    show("Tap a suggestion to insert it.")
                    names.forEach { n ->
                        completeRow.addView(chip(n) {
                            val pos = editor.selectionStart
                            val prefix = OrionStudioEditor.wordBefore(editor.text.toString(), pos)
                            editor.text.insert(pos, if (n.startsWith(prefix)) n.removePrefix(prefix) else n)
                            completeRow.removeAllViews()
                        })
                    }
                }
            }
        }.start()
    }

    private fun runFile() {
        val rel = relPath(); if (fileField.text.isBlank()) { show("Open or name a file first."); return }
        val text = editor.text.toString()
        val cmd = when (OrionStudioEditor.langOf(rel)) {
            "python" -> "python3 /workspace/$rel"; "javascript" -> "node /workspace/$rel"; "shell" -> "sh /workspace/$rel"
            "go" -> "go run /workspace/$rel"; "ruby" -> "ruby /workspace/$rel"; "php" -> "php /workspace/$rel"
            else -> { show("No runner for this file type."); return }
        }
        show("Running…")
        Thread {
            workspaceFile(rel)?.let { f -> runCatching { f.writeText(text) } }
            show(ShellRunner.run(applicationContext, "cd /workspace && $cmd 2>&1 | tail -n 80", OrionUbuntu.where(applicationContext)))
        }.start()
    }

    // ----- tools -----
    private fun refreshStatus() {
        Thread { show(runCatching { OrionStudio.overview(applicationContext) }.getOrElse { "status unavailable: ${it.message}" }) }.start()
    }

    private fun go(action: String, vararg extra: Pair<String, String>) {
        autoStatus = false
        show("Running $action…")
        val args = JSONObject().put("action", action).put("project", project.text.toString().trim())
        extra.forEach { args.put(it.first, it.second) }
        Thread { show(runCatching { OrionStudio.run(applicationContext, args) }.getOrElse { "error: ${it.message}" }) }.start()
    }

    private fun discover(source: String) {
        val q = searchField.text.toString().trim()
        if (q.isEmpty()) { Toast.makeText(this, "Type what to search for", Toast.LENGTH_SHORT).show(); return }
        show("Searching $source…")
        Thread { show(OrionStudio.discover(source, q)) }.start()
    }

    private fun renderRegistry() {
        registry.removeAllViews()
        val rows = OrionStudio.registryList(applicationContext).lines()
        OrionStudio.catalog.forEach { e ->
            val installed = rows.any { it.startsWith("✓ ${e.id} ") }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); background = panel()
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            }
            card.addView(label("${e.name}  ·  ${e.kind.name.lowercase()}", 14f, Theme.TEXT, true))
            card.addView(label(e.about, 12f, Theme.MUTED))
            val actions = mutableListOf<TextView>()
            if (!installed) actions += chip("Install") { go("registry_install", "id" to e.id); ui.postDelayed({ renderRegistry() }, 20000) }
            else {
                if (e.kind == OrionStudio.Kind.MCP) actions += chip("Tools") { go("mcp_tools", "id" to e.id) }
                if (e.run.isNotBlank()) actions += chip("Run") { go("registry_run", "id" to e.id) }
                actions += chip("Remove") { go("registry_remove", "id" to e.id); ui.postDelayed({ renderRegistry() }, 800) }
            }
            card.addView(row(*actions.toTypedArray()))
            registry.addView(card)
        }
    }
}
