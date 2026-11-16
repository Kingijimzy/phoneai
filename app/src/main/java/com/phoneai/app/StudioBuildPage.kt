package com.phoneai.app

import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** Build & test engine: runs the project's real build/test and reports what actually happened. */
internal class StudioBuildPage(host: StudioActivity) : StudioPage(host) {
    override val id = "build"
    override val title = "Build"
    private lateinit var verdict: TextView
    private lateinit var log: TextView
    private lateinit var history: LinearLayout
    private val prefs get() = host.getSharedPreferences("studio_build_history", 0)

    override fun build(root: LinearLayout) {
        root.addView(ui.section("Build & test"))
        verdict = ui.label("No run yet", 17f, Theme.TEXT, true)
        root.addView(verdict)
        root.addView(ui.row(ui.chip("Build") { run("build") }, ui.chip("Test") { run("test") }, ui.chip("Diagnostics") { run("diagnostics") }, ui.chip("Format") { run("format") }))
        log = ui.output(260); root.addView(log)
        root.addView(ui.section("History"))
        history = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        root.addView(history)
    }

    override fun onShow() = renderHistory()

    private fun run(action: String) {
        verdict.text = "Running $action…"; verdict.setTextColor(Theme.TEXT); log.text = ""
        host.bg {
            val out = OrionStudio.run(host, JSONObject().put("action", action).put("project", host.project))
            val first = out.lineSequence().firstOrNull().orEmpty()
            val ok = when (action) { "build", "test" -> OrionStudio.report(out, action).ok; else -> !out.startsWith("error") }
            val summary = if (action == "build" || action == "test") first else action.replaceFirstChar { it.uppercase() } + " finished"
            host.post {
                verdict.text = (if (ok) "✓ " else "✗ ") + summary
                verdict.setTextColor(if (ok) Theme.OK else Theme.DANGER)
                log.text = out.take(12000)
                remember(summary, ok); renderHistory()
            }
        }
    }

    private fun remember(summary: String, ok: Boolean) {
        val old = prefs.getString("items", "").orEmpty().lines().filter { it.isNotBlank() }
        val line = (if (ok) "1" else "0") + "|" + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()) + "|" + host.project + "|" + summary.replace('\n', ' ')
        prefs.edit().putString("items", (listOf(line) + old).take(12).joinToString("\n")).apply()
    }

    private fun renderHistory() {
        history.removeAllViews()
        val rows = prefs.getString("items", "").orEmpty().lines().filter { it.isNotBlank() }
        if (rows.isEmpty()) history.addView(ui.label("Runs appear here.", 12f, Theme.MUTED))
        rows.forEach { r ->
            val p = r.split('|', limit = 4)
            if (p.size == 4) history.addView(ui.label((if (p[0] == "1") "✓ " else "✗ ") + p[1] + "  " + p[2].ifBlank { "workspace" } + "  " + p[3], 12f, if (p[0] == "1") Theme.TEXT_SECONDARY else Theme.DANGER))
        }
    }
}
