package com.phoneai.app

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Integrated terminal: commands run in the selected runtime inside the current project folder. */
internal class StudioTerminalPage(host: StudioActivity) : StudioPage(host) {
    override val id = "terminal"
    override val title = "Terminal"
    private lateinit var out: TextView
    private lateinit var input: EditText
    private lateinit var runtime: TextView
    private lateinit var recent: TextView
    private val history = ArrayList<String>()
    private val buffer = StringBuilder()

    override fun build(root: LinearLayout) {
        root.addView(ui.section("Terminal"))
        runtime = ui.label("", 12f, Theme.TEXT_SECONDARY)
        root.addView(runtime)
        out = ui.output(300)
        root.addView(out)
        input = ui.field("command, e.g. python3 app.py")
        root.addView(input)
        root.addView(ui.row(
            ui.chip("Run") { run(input.text.toString()) },
            ui.chip("Clear") { buffer.setLength(0); out.text = "" },
            ui.chip("ls") { run("ls -la") },
            ui.chip("pwd") { run("pwd") },
            ui.chip("Processes") { run("ps aux | head -n 25") },
            ui.chip("Disk") { run("df -h /workspace | tail -n 2") }
        ))
        root.addView(ui.section("Recent"))
        recent = ui.output(220)
        root.addView(recent)
    }

    override fun onShow() {
        runtime.text = "Runtime: ${OrionUbuntu.label(host)} · /workspace/" + host.project
        recent.text = ShellCommandStore.recentTranscript(host, 20).ifBlank { "No saved terminal commands yet." }
    }

    private fun run(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        history.remove(cmd); history.add(0, cmd); if (history.size > 12) history.removeAt(12)
        input.setText("")
        append("\$ $cmd\n")
        host.bg {
            val result = OrionStudio.run(host, org.json.JSONObject().put("action", "terminal").put("command", cmd).put("project", host.project))
            host.post { append(result.trimEnd() + "\n\n") }
        }
    }

    private fun append(text: String) {
        buffer.append(text)
        if (buffer.length > 20000) buffer.delete(0, buffer.length - 20000)
        out.text = buffer.toString()
        host.scrollToBottom()
    }
}
