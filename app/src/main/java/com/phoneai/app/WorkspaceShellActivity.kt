package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** SAF-scoped workspace terminal. It never executes arbitrary OS shell commands. */
class WorkspaceShellActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("phoneai_workspace", MODE_PRIVATE) }
    private var workspace: Uri? = null
    private lateinit var output: TextView
    private lateinit var command: EditText
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        workspace = prefs.getString("tree_uri", null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(20))
            setBackgroundColor(Theme.BG)
        }
        root.addView(TextView(this).apply { text = "ORION / WORKSPACE TERMINAL"; textSize = 21f; setTextColor(Theme.TEXT) })
        root.addView(TextView(this).apply {
            text = "SAF-scoped files only. Allowlisted workspace commands; not a system shell."
            textSize = 13f; setTextColor(Theme.MUTED); setPadding(0, dp(6), 0, dp(10))
        })
        addButton(root, "Choose workspace folder") { chooseWorkspace() }
        addButton(root, "List workspace files") { runCommand("ls") }
        addButton(root, "Command help") { runCommand("help") }
        output = TextView(this).apply {
            text = "Workspace: ${workspace?.lastPathSegment ?: "not selected"}\nType help to see allowed commands."
            textSize = 13f; setTextColor(Theme.TEXT); setTextIsSelectable(true); setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        command = EditText(this).apply {
            hint = "run_command ls | cat file.txt | write file.txt|content"
            setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED); singleLine = true; imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { val line = text.toString(); setText(""); runCommand(line); true } else false
            }
        }
        root.addView(command)
        addButton(root, "RUN ALLOWLISTED COMMAND", true) { val line = command.text.toString(); command.setText(""); runCommand(line) }
        addButton(root, "Back") { finish() }
        setContentView(root)
    }

    private fun addButton(root: LinearLayout, label: String, primary: Boolean = false, action: () -> Unit) {
        root.addView(Button(this).apply { text = label; setTextColor(Theme.TEXT); isAllCaps = false; setOnClickListener { action() } },
            LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })
    }

    private fun chooseWorkspace() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        ), 731)
    }

    @Deprecated("Kept compatible with the current min SDK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 731 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
            workspace = uri; prefs.edit().putString("tree_uri", uri.toString()).apply()
            AgentPolicy.log(this, "workspace", "select-folder", true, "user selected SAF workspace")
            output.text = "Workspace selected.\nType help to see allowed commands."
        } catch (e: Exception) { output.text = "Could not persist folder access: ${e.message}" }
    }

    private fun runCommand(raw: String) {
        val line = raw.trim().removePrefix("run_command").trim()
        if (line.isBlank()) { append("No command supplied. Type help."); return }
        val parts = line.split(" ", limit = 2)
        val verb = parts[0].lowercase()
        val arg = parts.getOrElse(1) { "" }
        if (verb !in setOf("help", "pwd", "ls", "cat", "grep", "write", "patch", "rm")) {
            append("DENIED: command not allowlisted. No OS shell/process is executed.")
            AgentPolicy.log(this, "workspace:$verb", "dispatch", false, "command not allowlisted")
            return
        }
        val write = verb in setOf("write", "patch", "rm")
        if (workspace == null && verb !in setOf("help", "pwd")) { append("Select a workspace folder first."); return }
        val decision = AgentPolicy.evaluate(this, "workspace_shell", "workspace_$verb", dangerous = write)
        when (decision.decision) {
            AgentPolicy.Decision.DENY, AgentPolicy.Decision.PLAN_ONLY -> {
                append("BLOCKED: ${decision.reason}"); AgentPolicy.log(this, "workspace:$verb", "dispatch", false, decision.reason); return
            }
            AgentPolicy.Decision.NEED_APPROVAL -> {
                AlertDialog.Builder(this).setTitle("Approve workspace command?")
                    .setMessage("Command: $verb\nArguments: ${arg.take(240)}\n${if (write) "This changes workspace files." else "This reads workspace files."}\nRun once?")
                    .setNegativeButton("CANCEL") { _, _ -> AgentPolicy.log(this, "workspace:$verb", "dispatch", false, "user declined"); append("Cancelled.") }
                    .setPositiveButton("APPROVE") { _, _ -> AgentPolicy.log(this, "workspace:$verb", "dispatch", true, "user approved workspace command"); execute(verb, arg) }.show()
                return
            }
            AgentPolicy.Decision.ALLOW -> AgentPolicy.log(this, "workspace:$verb", "dispatch", null, decision.reason)
        }
        execute(verb, arg)
    }

    private fun execute(verb: String, arg: String) {
        try {
            val result = when (verb) {
                "help" -> "Allowed commands:\nhelp\npwd\nls\ncat filename\ngrep text|filename\nwrite filename|content\npatch filename|old text|new text\nrm filename (confirmation required)\n\nOnly files in the selected SAF root are addressable. No arbitrary shell commands."
                "pwd" -> workspace?.toString() ?: "(no workspace selected)"
                "ls" -> listFiles()
                "cat" -> readFile(arg.trim())
                "grep" -> { val p = arg.split("|", limit = 2); if (p.size != 2) "Usage: grep text|filename" else {
                    val data = readFile(p[1].trim()); if (data.startsWith("ERROR:")) data else data.lineSequence().filter { it.contains(p[0]) }.joinToString("\n").ifEmpty { "No matching lines." }
                } }
                "write" -> { val p = arg.split("|", limit = 2); if (p.size != 2) "Usage: write filename|content" else writeFile(p[0].trim(), p[1]) }
                "patch" -> { val p = arg.split("|", limit = 3); if (p.size != 3) "Usage: patch filename|old text|new text" else {
                    val before = readFile(p[0].trim())
                    if (before.startsWith("ERROR:")) before else if (!before.contains(p[1])) "ERROR: target text not found; file unchanged."
                    else writeFile(p[0].trim(), before.replaceFirst(p[1], p[2]))
                } }
                "rm" -> removeFile(arg.trim())
                else -> "ERROR: command not implemented."
            }
            AgentPolicy.log(this, "workspace:$verb", "result", !result.startsWith("ERROR:"), if (result.startsWith("ERROR:")) "operation failed; details shown in terminal" else "completed; output shown in terminal")
            append(result)
        } catch (e: Exception) { AgentPolicy.log(this, "workspace:$verb", "result", false, "workspace operation failed"); append("ERROR: ${e.message ?: e.javaClass.simpleName}") }
    }

    private data class Entry(val name: String, val mime: String, val uri: Uri)
    private fun rootUri(): Uri = workspace ?: throw IllegalStateException("Select a workspace folder first.")
    private fun entries(): List<Entry> {
        val tree = rootUri()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = mutableListOf<Entry>()
        contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue; val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: "application/octet-stream"
                out += Entry(name, mime, DocumentsContract.buildDocumentUriUsingTree(tree, id))
            }
        }
        return out
    }
    private fun safeName(name: String): String {
        val n = name.trim()
        require(n.isNotBlank() && n.length <= 180 && n !in setOf(".", "..") && '/' !in n && '\\' !in n) { "Use a single filename in the workspace root." }
        return n
    }
    private fun listFiles(): String = entries().joinToString("\n") { "${if (it.mime == DocumentsContract.Document.MIME_TYPE_DIR) "[DIR]" else "[FILE]"} ${it.name}" }.ifEmpty { "(workspace is empty)" }
    private fun findFile(name: String): Entry? = entries().firstOrNull { it.name == safeName(name) && it.mime != DocumentsContract.Document.MIME_TYPE_DIR }
    private fun readFile(name: String): String {
        val entry = findFile(name) ?: return "ERROR: file not found in workspace root."
        return contentResolver.openInputStream(entry.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(500_000) } ?: "ERROR: unable to open file."
    }
    private fun writeFile(name: String, content: String): String {
        val safe = safeName(name); val existing = findFile(safe)
        val uri = existing?.uri ?: DocumentsContract.createDocument(contentResolver,
            DocumentsContract.buildDocumentUriUsingTree(rootUri(), DocumentsContract.getTreeDocumentId(rootUri())), "text/plain", safe)
            ?: return "ERROR: provider refused to create file."
        contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content.take(500_000)) }
            ?: return "ERROR: unable to write file."
        return "Wrote ${content.length.coerceAtMost(500_000)} characters to $safe."
    }
    private fun removeFile(name: String): String {
        val entry = findFile(name) ?: return "ERROR: file not found in workspace root."
        AlertDialog.Builder(this).setTitle("Delete workspace file?").setMessage("Delete ${entry.name}? This cannot be undone.")
            .setNegativeButton("CANCEL", null).setPositiveButton("DELETE") { _, _ ->
                val deleted = DocumentsContract.deleteDocument(contentResolver, entry.uri)
                AgentPolicy.log(this, "workspace:rm", "delete-result", deleted, if (deleted) "user confirmed file deletion" else "provider refused deletion")
                append(if (deleted) "Deleted ${entry.name}." else "ERROR: deletion failed.")
            }.show()
        return "Deletion requires a second explicit confirmation."
    }
    private fun append(message: String) { output.append("\n\n> $message") }
}