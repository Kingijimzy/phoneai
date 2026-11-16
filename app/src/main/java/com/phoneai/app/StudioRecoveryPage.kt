package com.phoneai.app

import android.app.AlertDialog
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** Recovery system: preserve work, roll back changes, and resume interrupted tasks. */
internal class StudioRecoveryPage(host: StudioActivity) : StudioPage(host) {
    override val id = "recovery"
    override val title = "Recovery"
    private lateinit var label: EditText
    private lateinit var list: LinearLayout
    private lateinit var out: TextView
    private lateinit var interrupted: TextView

    override fun build(root: LinearLayout) {
        root.addView(ui.section("Recovery"))
        root.addView(ui.label("Snapshots are taken automatically before the Dev Agent starts and before formatting. Restoring takes a safety snapshot first.", 12f, Theme.MUTED))
        label = ui.field("snapshot label (optional)")
        root.addView(label)
        root.addView(ui.row(ui.chip("Snapshot now") { snapshot() }, ui.chip("Refresh") { load() },
            ui.chip("Git rollback…") { rollback() }, ui.chip("Run due jobs") { runJobs() }))
        out = ui.output(70); root.addView(out)
        root.addView(ui.section("Snapshots"))
        list = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(ui.section("Interrupted work — resume"))
        interrupted = ui.output(120); root.addView(interrupted)
    }

    override fun onShow() = load()

    private fun load() {
        host.bg {
            val snaps = OrionStudio.run(host, JSONObject().put("action", "snapshots"))
            val rows = snaps.lines().mapNotNull { l -> l.trim().split(' ').firstOrNull()?.takeIf { it.endsWith(".tar.gz") }?.let { it to l.trim().substringAfter(' ', "") } }
            val state = "WORKERS\n" + OrionWorkers.list(host).prependIndent("  ") + "\nSERVICES\n" + OrionDevspace.listServices().prependIndent("  ") + "\nJOBS\n" + OrionDevspace.listJobs(host).prependIndent("  ")
            host.post {
                list.removeAllViews()
                if (rows.isEmpty()) list.addView(ui.label("No snapshots yet.", 12f, Theme.MUTED))
                rows.forEach { (name, size) ->
                    val c = ui.card()
                    c.addView(ui.label(name, 12f, Theme.TEXT, true))
                    c.addView(ui.label(size, 11f, Theme.MUTED))
                    c.addView(ui.row(ui.chip("Restore") { confirm("Restore $name? Current files are snapshotted first.") { restore(name) } }))
                    list.addView(c)
                }
                interrupted.text = state.take(6000)
            }
        }
    }

    private fun snapshot() {
        out.text = "Saving…"
        host.bg {
            val r = OrionStudio.run(host, JSONObject().put("action", "snapshot").put("project", host.project).put("label", label.text.toString()))
            host.post { out.text = r.take(600); label.setText(""); load() }
        }
    }

    private fun restore(name: String) {
        out.text = "Restoring…"
        host.bg {
            val r = OrionStudio.run(host, JSONObject().put("action", "restore").put("name", name))
            host.post { out.text = r.take(600); load() }
        }
    }

    private fun rollback() = confirm("Discard uncommitted changes in this project and reset to HEAD? They are stashed first.") {
        out.text = "Rolling back…"
        host.bg {
            val r = OrionStudio.run(host, JSONObject().put("action", "rollback").put("project", host.project).put("ref", "HEAD").put("confirm", true))
            host.post { out.text = r.take(600) }
        }
    }

    private fun runJobs() {
        out.text = "Running due jobs…"
        host.bg { runCatching { OrionDevspace.runDue(host) }; host.post { out.text = "Due jobs ran."; load() } }
    }

    private fun confirm(message: String, then: () -> Unit) {
        AlertDialog.Builder(host).setMessage(message).setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ -> then() }.show()
    }
}
