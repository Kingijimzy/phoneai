package com.phoneai.app

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Debugger: breakpoints, stack, variables and execution inspection (pdb for Python, gdb for C/C++, traces for Node). */
internal class StudioDebugPage(host: StudioActivity) : StudioPage(host) {
    override val id = "debug"
    override val title = "Debug"
    private lateinit var file: EditText
    private lateinit var points: EditText
    private lateinit var commands: EditText
    private lateinit var stack: TextView
    private lateinit var vars: TextView
    private lateinit var raw: TextView

    override fun build(root: LinearLayout) {
        root.addView(ui.section("Debugger"))
        file = ui.field("file to debug, e.g. app.py")
        points = ui.field("breakpoint lines, e.g. 12, 30")
        commands = ui.field("extra debugger commands, e.g. p total; p items")
        root.addView(file); root.addView(points); root.addView(commands)
        root.addView(ui.row(ui.chip("Run under debugger") { run(false) }, ui.chip("Run to crash") { run(true) }))
        root.addView(ui.section("Call stack"))
        stack = ui.output(70); root.addView(stack)
        root.addView(ui.section("Variables"))
        vars = ui.output(90); root.addView(vars)
        root.addView(ui.section("Debugger output"))
        raw = ui.output(140); root.addView(raw)
    }

    override fun onShow() { host.currentFile()?.let { if (file.text.isBlank()) file.setText(it) } }

    private fun run(untilCrash: Boolean) {
        val lines = if (untilCrash) emptyList() else points.text.toString().split(',', ' ').mapNotNull { it.trim().toIntOrNull() }
        val extra = commands.text.toString().split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val rel = (host.project.trim('/').let { if (it.isEmpty()) "" else "$it/" }) + file.text.toString().trim().trim('/')
        raw.text = "Running…"; stack.text = ""; vars.text = ""
        host.bg {
            val a = JSONObject().put("action", "debug").put("file", rel).put("breakpoints", JSONArray(lines)).put("commands", JSONArray(extra))
            val result = OrionStudio.run(host, a)
            host.post {
                raw.text = result.take(9000)
                stack.text = result.lines().filter { it.trimStart().startsWith(">") || it.contains("File \"") || Regex("^#\\d+").containsMatchIn(it.trim()) }.joinToString("\n").ifBlank { "(no frames captured)" }.take(2500)
                val idx = result.indexOf("{")
                vars.text = if (idx >= 0) result.substring(idx).take(2500) else "(no variables captured — set a breakpoint line)"
            }
        }
    }
}
