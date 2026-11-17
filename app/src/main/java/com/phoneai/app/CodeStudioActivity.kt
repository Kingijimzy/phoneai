package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** Mobile IDE backed by Orion's shared /workspace used by CPython and Alpine. */
class CodeStudioActivity : Activity() {
    private lateinit var fs: OrionPythonWorkspace
    private lateinit var explorer: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var editor: EditText
    private lateinit var numbers: TextView
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var command: EditText
    private lateinit var terminalPanel: LinearLayout
    private lateinit var runtimeChip: TextView
    private val openTabs = linkedSetOf<String>()
    private var current: String? = null
    private var dirty = false
    private var loading = false
    private var explorerVisible = false
    private var terminalVisible = false
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(c: Int = Theme.PANEL) = GradientDrawable().apply { setColor(c); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Theme.LINE) }
    private fun txt(s: String, size: Float = 12f, color: Int = Theme.TEXT) = TextView(this).apply { text = s; textSize = size; setTextColor(color) }
    private fun chip(s: String, action: () -> Unit) = txt(s, 12f).apply {
        typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(dp(10), dp(9), dp(10), dp(9))
        background = bg(Theme.PANEL2); isClickable = true; setOnClickListener { action() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG; window.navigationBarColor = Theme.BG
        fs = OrionPythonWorkspace(applicationContext)
        buildUi(); refreshFiles()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(9)); setBackgroundColor(Theme.BG) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(txt("ORION CODE STUDIO", 18f))
            addView(txt("One workspace for editor, terminal and Orion", 11f, Theme.MUTED))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        runtimeChip = chip(OrionUbuntu.label(this)) { showRuntimeMenu() }
        header.addView(runtimeChip)
        header.addView(chip("⋮") { showMenu() }); root.addView(header)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(9), 0, dp(7)) }
        val buttons = listOf("Files", "Terminal", "Run", "Save")
        buttons.forEachIndexed { i, s ->
            val run: () -> Unit = when (i) {
                0 -> ({ toggleExplorer() })
                1 -> ({ toggleTerminal() })
                2 -> ({ runFile(); toggleTerminal(true) })
                else -> ({ saveFile(); Unit })
            }
            actions.addView(chip(s, run), LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(5) })
        }
        root.addView(actions)
        val expScroll = ScrollView(this).apply { background = bg(); isFillViewport = true }
        explorer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(5), dp(6), dp(5)) }
        expScroll.addView(explorer)
        expScroll.visibility = android.view.View.GONE
        root.addView(expScroll, LinearLayout.LayoutParams(-1, dp(126)).apply { topMargin = dp(2); bottomMargin = dp(5) })

        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }, LinearLayout.LayoutParams(-1, dp(35)))
        status = txt("No file open", 10f, Theme.MUTED); root.addView(status, LinearLayout.LayoutParams(-1, dp(24)))

        val edit = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = bg(0xFF0D1117.toInt()) }
        numbers = txt("1", 11f, Theme.MUTED).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.TOP or Gravity.END; setPadding(dp(7), dp(11), dp(7), dp(10)) }
        edit.addView(numbers, LinearLayout.LayoutParams(dp(34), -1))
        editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(0xFFE4E8EF.toInt()); setHintTextColor(Theme.MUTED)
            hint = "Choose a file or create one"; gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(false); setHorizontallyScrolling(true); setPadding(dp(5), dp(11), dp(8), dp(10)); background = null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!loading) { dirty = true; updateStatus(); updateNumbers() }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        edit.addView(editor, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(edit, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(7) })

        val outHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        outHead.addView(txt("OUTPUT / TERMINAL", 10f, Theme.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
        outHead.addView(chip("Run file") { runFile() })
        outHead.addView(chip("Clear") { output.text = "Ready." })
        terminalPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        terminalPanel.addView(outHead)
        val outScroll = ScrollView(this).apply { background = bg(); isFillViewport = true }
        output = txt("Ready. Run a file or enter a Linux command.", 11f, Theme.TEXT_SECONDARY).apply {
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(dp(9), dp(8), dp(9), dp(8))
        }
        outScroll.addView(output); terminalPanel.addView(outScroll, LinearLayout.LayoutParams(-1, dp(118)).apply { topMargin = dp(5) })

        val cmdRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        command = EditText(this).apply {
            hint = "Linux command in /workspace"; setSingleLine(true); textSize = 12f; typeface = Typeface.MONOSPACE
            setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED); background = bg(); setPadding(dp(9), dp(7), dp(9), dp(7))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { runCommand(); true } else false }
        }
        cmdRow.addView(command, LinearLayout.LayoutParams(0, dp(42), 1f))
        cmdRow.addView(chip("Send") { runCommand() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(5) })
        terminalPanel.addView(cmdRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        root.addView(terminalPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        setContentView(root)
    }

    private fun toggleExplorer() {
        explorerVisible = !explorerVisible
        (explorer.parent as? android.view.View)?.visibility =
            if (explorerVisible) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun toggleTerminal(forceOpen: Boolean = false) {
        terminalVisible = if (forceOpen) true else !terminalVisible
        terminalPanel.visibility = if (terminalVisible) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showRuntimeMenu() {
        val choices = arrayOf("Automatic (Ubuntu when installed, otherwise Alpine)", "Ubuntu 24.04", "Alpine Linux")
        val selected = when (OrionUbuntu.choice(this)) { "ubuntu" -> 1; "linux" -> 2; else -> 0 }
        AlertDialog.Builder(this).setTitle("Execution runtime").setSingleChoiceItems(choices, selected) { dialog, which ->
            val value = when (which) { 1 -> "ubuntu"; 2 -> "linux"; else -> "auto" }
            OrionUbuntu.setChoice(this, value)
            runtimeChip.text = OrionUbuntu.label(this) + if (value == "auto") " · Auto" else ""
            dialog.dismiss()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun showMenu() {
        AlertDialog.Builder(this).setTitle("Code Studio").setItems(arrayOf("New file", "New folder", "Import project folder", "Create project", "Refresh files", "Open full terminal", "Workspace information")) { _, n ->
            when (n) {
                0 -> newFile()
                1 -> newFolder()
                2 -> importFolder()
                3 -> newProject()
                4 -> refreshFiles()
                5 -> startActivity(Intent(this, TerminalActivity::class.java))
                else -> output.text = "Code Studio edits Orion's shared /workspace. Files are shared with Orion's coding tools and selected Linux runtime. Existing files are backed up before overwrite; deleted files are moved to Orion's recycle area."
            }
        }.show()
    }

    private fun newProject() {
        val box = EditText(this).apply { hint = "project_name"; setSingleLine(true); setTextColor(Theme.TEXT) }
        AlertDialog.Builder(this).setTitle("Create Linux project").setMessage("Project name: letters, numbers, dot, dash or underscore.")
            .setView(box).setNegativeButton("Cancel", null).setPositiveButton("Next") { _, _ ->
                val name = box.text.toString().trim()
                if (!OrionDevspace.validName(name)) { output.text = "Invalid project name."; return@setPositiveButton }
                AlertDialog.Builder(this).setTitle("Choose project template")
                    .setItems(arrayOf("Python CLI", "Python API", "Node.js", "Static web")) { _, kind ->
                        val type = when (kind) { 0 -> "python"; 1 -> "api"; 2 -> "node"; else -> "web" }
                        val script = OrionDevspace.scaffold(name, type)
                        if (script == null) { output.text = "Unsupported project template."; return@setItems }
                        execute(script)
                    }.show()
            }.show()
    }

    private fun refreshFiles() {
        explorer.removeAllViews()
        val listing = fs.list()
        if (listing == "(workspace empty)" || listing.isBlank()) { explorer.addView(txt("Workspace empty — create a file or import a project.", 11f, Theme.MUTED)); return }
        if (listing.startsWith("error:")) { explorer.addView(txt(listing, 11f, Theme.DANGER)); return }
        val files = listing.lines().filter { it.isNotBlank() }.distinct().sorted()
        val dirs = sortedSetOf<String>()
        files.forEach { p -> val parts = p.split("/"); for (i in 1 until parts.size) dirs.add(parts.take(i).joinToString("/")) }
        val rows = (dirs.map { it to true } + files.map { it to false })
            .sortedWith(compareBy<Pair<String, Boolean>>({ it.first.count { c -> c == '/' } }, { it.first.lowercase(Locale.ROOT) }, { it.second }))
        rows.take(500).forEach { (path, dir) ->
            val depth = path.count { it == '/' }
            val row = txt((if (dir) "▾  " else "   ") + path.substringAfterLast("/"), 11f,
                if (dir) Theme.SILVER else if (path == current) Theme.TEXT else Theme.TEXT_SECONDARY).apply {
                typeface = if (dir) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
                setPadding(dp(7 + depth * 12), dp(6), dp(5), dp(6))
                if (!dir) setOnClickListener { openFile(path) }
            }
            explorer.addView(row)
        }
    }

    private fun openFile(path: String) {
        if (dirty) {
            AlertDialog.Builder(this).setTitle("Unsaved changes").setMessage("Save the current file before opening another?")
                .setPositiveButton("Save") { _, _ -> if (saveFile()) load(path) }
                .setNegativeButton("Discard") { _, _ -> dirty = false; load(path) }
                .setNeutralButton("Cancel", null).show()
        } else load(path)
    }
    private fun load(path: String) {
        val value = fs.read(path)
        if (value.startsWith("error:")) { output.text = value; return }
        current = path; openTabs.add(path); loading = true; editor.setText(value); editor.setSelection(editor.text.length); loading = false
        dirty = false; drawTabs(); updateStatus(); updateNumbers(); refreshFiles()
    }
    private fun drawTabs() {
        tabs.removeAllViews()
        openTabs.forEach { p ->
            tabs.addView(txt((if (p == current) "● " else "") + p.substringAfterLast("/") + " ×", 10f,
                if (p == current) Theme.TEXT else Theme.MUTED).apply {
                setPadding(dp(9), dp(8), dp(9), dp(7)); background = bg(if (p == current) Theme.PANEL2 else Theme.PANEL)
                setOnClickListener { if (p == current) closeTab(p) else openFile(p) }
            }, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = dp(4) })
        }
    }
    private fun closeTab(path: String) {
        if (path == current && dirty) {
            AlertDialog.Builder(this).setMessage("Discard unsaved changes?")
                .setPositiveButton("Discard") { _, _ ->
                    openTabs.remove(path); current = openTabs.lastOrNull()
                    if (current == null) clearEditor() else load(current!!)
                }.setNegativeButton("Cancel", null).show()
        } else {
            openTabs.remove(path)
            if (path == current) { current = openTabs.lastOrNull(); if (current == null) clearEditor() else load(current!!) } else drawTabs()
        }
    }
    private fun clearEditor() {
        loading = true; editor.setText(""); loading = false; dirty = false; current = null
        drawTabs(); updateStatus(); updateNumbers()
    }
    private fun updateStatus() {
        status.text = (current ?: "No file open") + if (dirty) "   ● Modified" else "   · Saved"
        status.setTextColor(if (dirty) Theme.WARN else Theme.MUTED)
    }
    private fun updateNumbers() { numbers.text = (1..(editor.text.toString().count { it == '\n' } + 1)).joinToString("\n") }

    private fun saveFile(): Boolean {
        val path = current ?: run { output.text = "Create or open a file first."; return false }
        val result = fs.write(path, editor.text.toString()); output.text = result
        if (result.startsWith("saved ")) { dirty = false; updateStatus(); refreshFiles(); return true }
        return false
    }
    private fun validPath(p: String) = p.isNotBlank() && !p.startsWith("/") && p.split("/").all { it.isNotBlank() && it != "." && it != ".." }
    private fun newFile() {
        val box = EditText(this).apply { hint = "src/main.py"; setSingleLine(true); setTextColor(Theme.TEXT) }
        AlertDialog.Builder(this).setTitle("New file").setMessage("Path relative to /workspace").setView(box)
            .setNegativeButton("Cancel", null).setPositiveButton("Create") { _, _ ->
                val p = box.text.toString().trim()
                if (!validPath(p)) { output.text = "Invalid relative path."; return@setPositiveButton }
                if (!fs.read(p).startsWith("error:")) { output.text = "File already exists; not overwritten."; return@setPositiveButton }
                val starter = when (p.substringAfterLast(".").lowercase()) {
                    "py" -> "def main():\n    print('Hello from Orion')\n\nif __name__ == '__main__':\n    main()\n"
                    "js" -> "console.log('Hello from Orion');\n"
                    "html" -> "<!doctype html>\n<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body><h1>Hello from Orion</h1></body></html>\n"
                    "kt" -> "fun main() {\n    println(\"Hello from Orion\")\n}\n"
                    else -> ""
                }
                val result = fs.write(p, starter); output.text = result
                if (result.startsWith("saved ")) load(p)
                refreshFiles()
            }.show()
    }
    private fun newFolder() {
        val box = EditText(this).apply { hint = "src/components"; setSingleLine(true); setTextColor(Theme.TEXT) }
        AlertDialog.Builder(this).setTitle("New folder").setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val p = box.text.toString().trim().trimEnd('/')
                if (!validPath(p)) { output.text = "Invalid folder path."; return@setPositiveButton }
                output.text = fs.write(p + "/.gitignore", "# Orion workspace folder\n"); refreshFiles()
            }.show()
    }

    private fun importFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        ), 732)
    }
    @Deprecated("Kept compatible with current min SDK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 732 || resultCode != RESULT_OK) return
        val tree = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(tree, data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        output.text = "Importing project into /workspace/imports…"
        Thread {
            val result = importTree(tree)
            runOnUiThread { output.text = result; refreshFiles() }
        }.apply { name = "OrionCodeStudioImport"; isDaemon = true; start() }
    }
    private data class Doc(val id: String, val name: String, val mime: String)
    private fun importTree(tree: Uri): String {
        var copied = 0; var skipped = 0; var total = 0L
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return "Invalid folder."
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val raw = runCatching {
            contentResolver.query(rootDoc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull().orEmpty().ifBlank { "project" }
        val folder = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').take(70).ifBlank { "project" }
        val stack = ArrayDeque<Pair<String, String>>(); stack.add(rootId to "imports/" + folder)
        while (stack.isNotEmpty() && copied + skipped < 5000) {
            val (parent, path) = stack.removeLast()
            val children = mutableListOf<Doc>()
            try {
                contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent),
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null, null, null)?.use { c -> while (c.moveToNext()) children.add(Doc(c.getString(0), c.getString(1) ?: "file", c.getString(2) ?: "")) }
            } catch (_: Exception) { skipped++; continue }
            children.forEach { child ->
                if (child.name.startsWith(".") || child.name in setOf("node_modules", ".git", ".gradle", "build", "dist", ".idea", ".venv", "venv")) { skipped++; return@forEach }
                val name = child.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "file" }
                val target = path + "/" + name
                if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) { stack.add(child.id to target); return@forEach }
                if (child.mime.startsWith("image/") || child.mime.startsWith("video/") || child.mime.startsWith("audio/") || child.mime == "application/octet-stream") { skipped++; return@forEach }
                try {
                    if (!fs.read(target).startsWith("error:")) { skipped++; return@forEach }
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, child.id)
                    var exceededImportLimit = false
                    val dataBytes = contentResolver.openInputStream(uri)?.use { stream ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val n = stream.read(chunk)
                            if (n < 0) break
                            if (buffer.size().toLong() + n > OrionPythonWorkspace.MAX_FILE_BYTES ||
                                total + buffer.size().toLong() + n > 268435456L) {
                                exceededImportLimit = true
                                break
                            }
                            buffer.write(chunk, 0, n)
                        }
                        buffer.toByteArray()
                    } ?: run { skipped++; return@forEach }
                    if (exceededImportLimit || dataBytes.isEmpty() || dataBytes.any { it == 0.toByte() }) {
                        skipped++; return@forEach
                    }
                    if (fs.write(target, dataBytes.toString(Charsets.UTF_8)).startsWith("saved ")) { copied++; total += dataBytes.size } else skipped++
                } catch (_: Exception) { skipped++ }
            }
        }
        return "IMPORT COMPLETE\nFiles imported: " + copied + "\nSkipped: " + skipped + "\nSize: " + (total / 1024) + " KiB\nTarget: /workspace/imports/" + folder + "\nOriginal folder unchanged."
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun runFile() {
        val p = current ?: run { output.text = "Open a file first."; return }
        if (dirty && !saveFile()) return
        val cmd = when (p.substringAfterLast(".").lowercase()) {
            "py" -> "python3 " + quote(p)
            "js", "mjs", "cjs" -> "node " + quote(p)
            "sh" -> "sh " + quote(p)
            else -> { output.text = "No automatic Run profile for this file type. Enter a project command below."; return }
        }
        execute("cd /workspace && " + cmd)
    }
    private fun runCommand() {
        val value = command.text.toString().trim()
        if (value.isBlank()) return
        command.setText(""); execute("cd /workspace && " + value)
    }
    private fun execute(cmd: String) {
        val where = OrionUbuntu.where(applicationContext)
        val runtime = OrionUbuntu.label(applicationContext)
        output.text = "$ " + cmd + "\nRunning in $runtime…"
        Thread {
            val result = runCatching { ShellRunner.run(applicationContext, cmd, where) }
                .getOrElse { "error: " + (it.message ?: it.javaClass.simpleName) }
            runOnUiThread { output.text = "$ " + cmd + "\nRuntime: " + runtime + "\n\n" + result }
        }.apply { name = "OrionCodeStudioCommand"; isDaemon = true; start() }
    }
    override fun onBackPressed() {
        if (!dirty) { super.onBackPressed(); return }
        AlertDialog.Builder(this).setTitle("Unsaved changes").setMessage("Save changes before leaving Code Studio?")
            .setPositiveButton("Save") { _, _ -> if (saveFile()) super.onBackPressed() }
            .setNegativeButton("Discard") { _, _ -> super.onBackPressed() }.setNeutralButton("Stay", null).show()
    }
}