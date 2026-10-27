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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Orion Terminal: a built-in shell in Orion's private workspace, a bridge to Termux, and an "Ask AI" helper that proposes (never runs) a command. */
class TerminalActivity : Activity() {
    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var backendBtn: TextView
    private var termux = false
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
        backendBtn = chip("Built-in") { termux = !termux; refreshBackend() }
        top.addView(backendBtn); root.addView(top)
        out = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(0xFFCFE8CF.toInt()); setTextIsSelectable(true); setPadding(dp(10), dp(10), dp(10), dp(10)) }
        scroll = ScrollView(this).apply { background = GradientDrawable().apply { setColor(0xFF0E0E0E.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFF2E2E2E.toInt()) } }
        scroll.addView(out)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10); bottomMargin = dp(10) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "command (e.g. ls, cat notes.txt)"; typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(0xFFF0F0F0.toInt()); setHintTextColor(0xFF777777.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE; maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = GradientDrawable().apply { setColor(0xFF151515.toInt()); cornerRadius = dp(12).toFloat() }
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(chip("Run") { runInput() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        root.addView(row)
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        tools.addView(chip("✨ Ask AI") { askAi() }, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(chip("Clear") { out.text = "" }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        tools.addView(chip("Workspace") { input.setText("ls -la"); runInput() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        root.addView(tools)
        setContentView(root)
        refreshBackend()
        append("Orion Terminal. Built-in runs inside Orion's private workspace. Termux gives you python, git, node and more.\n")
    }

    private fun refreshBackend() { backendBtn.text = if (termux) "Termux" else "Built-in"; append("— using ${if (termux) "Termux" else "built-in shell"} —\n") }

    private fun append(s: String) { out.append(s); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }

    private fun runInput() {
        val cmd = input.text.toString().trim(); if (cmd.isEmpty()) return
        when (ShellPolicy.classify(cmd)) {
            ShellRisk.BLOCKED -> {
                append("$ $cmd\nBlocked: this command is on Orion's never-run list (destructive, privileged, or touches Orion's private data).\n")
                return
            }
            ShellRisk.ASK -> {
                AlertDialog.Builder(this)
                    .setTitle("Confirm shell command")
                    .setMessage("This command is outside Orion's automatic safe allowlist. Review it carefully before running:\n\n$cmd\n\nRun this command once?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Run once") { _, _ -> executeCommand(cmd) }
                    .show()
                return
            }
            ShellRisk.SAFE -> executeCommand(cmd)
        }
    }

    private fun executeCommand(cmd: String) {
        input.setText(""); append("$ $cmd\n")
        val where = if (termux) "termux" else "builtin"
        Thread { val r = ShellRunner.run(applicationContext, cmd, where); runOnUiThread { append(r + "\n") } }.start()
    }

    private fun askAi() {
        val box = EditText(this).apply { hint = "What do you want to do?"; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        AlertDialog.Builder(this).setTitle("Ask AI for a command").setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Suggest") { _, _ ->
                val task = box.text.toString().trim(); if (task.isEmpty()) return@setPositiveButton
                append("… asking AI\n")
                Thread {
                    val reply = try {
                        val sys = "You write ONE shell command for " + (if (termux) "Termux (bash, full Linux tools)" else "Android's built-in sh/toybox in a private folder") +
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
