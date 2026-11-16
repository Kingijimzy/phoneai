package com.phoneai.app

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Orion Dev Agent: hand it a coding objective and it inspects, plans, edits, executes, debugs and verifies as a
 * worker coordinated by the Central Brain. This page shows the live system: objective graph, workers, services, jobs.
 */
internal class StudioAgentPage(host: StudioActivity) : StudioPage(host) {
    override val id = "agent"
    override val title = "Agent"
    private lateinit var goal: EditText
    private lateinit var live: TextView
    private lateinit var result: TextView
    private val refresh = object : Runnable {
        override fun run() { if (host.currentPageId() == id) { load(); host.postDelayed(this, 4000) } }
    }

    override fun build(root: LinearLayout) {
        root.addView(ui.section("Orion Dev Agent"))
        root.addView(ui.label("Describe what to build or fix. The agent snapshots your work first, then inspects, plans, edits, runs, debugs and verifies with real results.", 12f, Theme.MUTED))
        goal = ui.field("e.g. Build a Flask notes API with tests in notes-api", multiline = true)
        root.addView(goal)
        root.addView(ui.row(
            ui.chip("Send to Dev Agent") { send() },
            ui.chip("Refresh") { load() },
            ui.chip("Open chat") { host.openChat() }
        ))
        result = ui.label("", 12f, Theme.TEXT_SECONDARY)
        root.addView(result)
        root.addView(ui.section("Live system — Central Brain"))
        live = ui.output(260)
        root.addView(live)
    }

    override fun onShow() { load(); host.postDelayed(refresh, 4000) }

    private fun send() {
        val objective = goal.text.toString().trim()
        if (objective.length < 8) { host.toast("Describe the goal in a sentence"); return }
        result.text = "Starting…"
        host.bg {
            val r = OrionStudio.run(host, JSONObject().put("action", "dev_task").put("objective", objective).put("project", host.project))
            host.post { result.text = r.take(500); goal.setText(""); load() }
        }
    }

    private fun load() {
        host.bg {
            val t = runCatching { OrionStudio.overview(host) }.getOrElse { "status unavailable: ${it.message}" }
            host.post { live.text = t.take(8000) }
        }
    }
}
