package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

/** Orion Terminal: built-in Android shell, managed Alpine Linux, and an Ask AI helper. */
class TerminalActivity : Activity() {
    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var backendBtn: TextView
    private lateinit var terminalView: TerminalView
    private lateinit var commandRow: LinearLayout
    private lateinit var toolsRow: LinearLayout
    private enum class Backend { BUILTIN, LINUX }
    private var backend = Backend.BUILTIN
    @Volatile private var terminalSession: TerminalSession? = null
    @Volatile private var terminalStarting = false
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun chip(caption: String, action: () -> Unit) = TextView(this).apply {
        text = caption; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFE4E4E4.toInt()); gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply { setColor(0xFF242424.toInt()); cornerRadius = dp(11).toFloat(); setStroke(dp(1), 0xFF444444.toInt()) }
        isClickable = true; setOnClickListener { action() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF090909.toInt(); window.navigationBarColor = 0xFF090909.toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF090909.toInt()); setPadding(dp(14), dp(40), dp(14), dp(12)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = "Terminal"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFF2F2F2.toInt()); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        backendBtn = chip("Built-in") { switchBackend() }
        top.addView(backendBtn); root.addView(top)
        out = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(0xFFCFE8CF.toInt()); setTextIsSelectable(true); setPadding(dp(10), dp(10), dp(10), dp(10)) }
        scroll = ScrollView(this).apply { background = GradientDrawable().apply { setColor(0xFF0E0E0E.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFF2E2E2E.toInt()) } }
        scroll.addView(out)
        terminalView = TerminalView(this, null).apply { visibility = android.view.View.GONE }
        val terminalFrame = FrameLayout(this)
        terminalFrame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        terminalFrame.addView(terminalView, FrameLayout.LayoutParams(-1, -1))
        root.addView(terminalFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10); bottomMargin = dp(10) })
        commandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val row = commandRow
        input = EditText(this).apply {
            hint = "command (e.g. ls, cat notes.txt)"; typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(0xFFF0F0F0.toInt()); setHintTextColor(0xFF777777.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE; maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = GradientDrawable().apply { setColor(0xFF151515.toInt()); cornerRadius = dp(12).toFloat() }
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(chip("Run") { runInput() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        root.addView(row)
        toolsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        val tools = toolsRow
        tools.addView(chip("✨ Ask AI") { askAi() }, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(chip("Clear") { out.text = "" }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        tools.addView(chip("Workspace") { input.setText("ls -la"); runInput() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        root.addView(tools)
        setContentView(root)
        refreshBackend()
        append("Orion Terminal. Built-in runs in Orion's private workspace; Linux runs in managed Alpine Linux.\n")
    }

    private fun switchBackend() {
        if (terminalStarting) return
        if (backend == Backend.LINUX) {
            terminalSession?.finishIfRunning()
            terminalSession = null
            terminalView.visibility = android.view.View.GONE
            scroll.visibility = android.view.View.VISIBLE
            commandRow.visibility = android.view.View.VISIBLE
            toolsRow.visibility = android.view.View.VISIBLE
            backend = Backend.BUILTIN
            refreshBackend()
            return
        }
        backend = Backend.LINUX
        terminalStarting = true
        backendBtn.isEnabled = false
        refreshBackend()
        append("Preparing persistent Alpine terminal…\n")
        Thread {
            try {
                val config = ShellRunner.prepareLinuxTerminalConfig(applicationContext)
                runOnUiThread {
                    if (isFinishing || isDestroyed || backend != Backend.LINUX) return@runOnUiThread
                    terminalStarting = false
                    backendBtn.isEnabled = true
                    val client = OrionTerminalClient(terminalView) { finished ->
                        if (terminalSession === finished) terminalSession = null
                        if (!isFinishing && !isDestroyed) append("\n[Linux session exited: ${finished.exitStatus}]\n")
                    }
                    terminalView.setTerminalViewClient(client)
                    // Termux TerminalView does not create its renderer in the constructor.
                    // Initialize it before attachSession(), whose updateSize() reads font metrics.
                    terminalView.setTextSize(12)
                    val session = TerminalSession(
                        config.executable, config.workingDirectory, config.arguments,
                        config.environment, 2000, client
                    )
                    terminalSession = session
                    scroll.visibility = android.view.View.GONE
                    commandRow.visibility = android.view.View.GONE
                    toolsRow.visibility = android.view.View.GONE
                    terminalView.visibility = android.view.View.VISIBLE
                    terminalView.attachSession(session)
                    terminalView.requestFocus()
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    terminalStarting = false
                    backendBtn.isEnabled = true
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    backend = Backend.BUILTIN
                    refreshBackend()
                    append("Could not open Alpine terminal: " + (failure.message ?: failure.javaClass.simpleName) + "\n")
                }
            }
        }.apply { name = "OrionLinuxTerminalStart"; isDaemon = true; start() }
    }

    private fun refreshBackend() {
        backendBtn.text = if (backend == Backend.BUILTIN) "Built-in" else "Linux"
        append("— using " + (if (backend == Backend.BUILTIN) "built-in shell" else "Alpine Linux PTY") + " —\n")
    }
    private fun append(s: String) {
        // TextView cannot render VT100 control sequences. Consume clear-screen commands
        // and remove unsupported ANSI sequences instead of printing escape bytes literally.
        if (s.contains("\u001B[H") || s.contains("\u001B[2J") || s.contains("\u001B[H\u001B[J")) out.text = ""
        val safe = s
            .replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
            .replace(Regex("\u001B\\][^\u0007]*(?:\u0007|\u001B\\\\)"), "")
        if (safe.isNotEmpty()) out.append(safe)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun runInput() {
        val cmd = input.text.toString().trim(); if (cmd.isEmpty()) return
        if (ShellPolicy.classify(cmd) == ShellRisk.BLOCKED) {
            append("$ $cmd\nBlocked: this command is on Orion's catastrophic hard-block list (root/system wipe, raw block-device write, or fork bomb).\n")
            return
        }
        executeCommand(cmd)
    }

    private fun executeCommand(cmd: String) {
        input.setText("")
        if (cmd.equals("clear", ignoreCase = true) || cmd.equals("cls", ignoreCase = true)) {
            if (backend == Backend.LINUX) terminalSession?.write("clear\n".toByteArray(), 0, 6) else out.text = ""
            return
        }
        if (backend == Backend.LINUX) {
            val session = terminalSession
            if (session == null || !session.isRunning) {
                append("Linux terminal is not connected. Switch back to Built-in and reopen Linux.\n")
                return
            }
            val bytes = (cmd + "\n").toByteArray(Charsets.UTF_8)
            session.write(bytes, 0, bytes.size)
            return
        }
        append("$ $cmd\n")
        append("⏳ Running — live output will appear below…\n")
        Thread {
            val r = ShellRunner.runStreaming(applicationContext, cmd, "builtin") { chunk -> runOnUiThread { append(chunk) } }
            runOnUiThread { when {
                r.startsWith("exit=") -> append("\n[" + r.substringBefore('\n') + "]\n")
                r.startsWith("error:") -> append("\n" + r + "\n")
                r.isNotBlank() -> append("\n" + r + "\n")
                else -> append("\n[Command finished]\n")
            } }
        }.apply { name = "OrionTerminalCommand"; isDaemon = true; start() }
    }

    override fun onDestroy() {
        terminalStarting = false
        terminalSession?.finishIfRunning()
        terminalSession = null
        super.onDestroy()
    }

    private fun askAi() {
        val box = EditText(this).apply { hint = "What do you want to do?"; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        AlertDialog.Builder(this).setTitle("Ask AI for a command").setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Suggest") { _, _ ->
                val task = box.text.toString().trim(); if (task.isEmpty()) return@setPositiveButton
                append("… asking AI\n")
                Thread {
                    val reply = try {
                        val sys = "You write ONE shell command for " + (if (backend == Backend.BUILTIN) "Android shell/toybox in a private folder" else "Orion managed Alpine Linux ARM64 environment") +
                            ". Output only the command, no explanation, no code fences. Never use sudo, never delete outside the current folder, never read secrets."
                        Nim(applicationContext).chat(JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", task)), false, 200, 0.1)
                            .trim().removePrefix("```sh").removePrefix("```bash").removePrefix("```").removeSuffix("```").trim().lineSequence().firstOrNull().orEmpty()
                    } catch (e: Exception) { "" }
                    runOnUiThread {
                        if (reply.isBlank()) append("AI could not suggest a command (check your AI key).\n")
                        else { input.setText(reply); input.setSelection(reply.length); append("AI suggests (review, then tap Run): $reply\n") }
                    }
                }.start()
            }.show()
    }
}
