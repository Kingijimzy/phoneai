package com.phoneai.app

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** Git integration: branches, status, diffs, commits and repository operations. */
internal class StudioGitPage(host: StudioActivity) : StudioPage(host) {
    override val id = "git"
    override val title = "Git"
    private lateinit var branch: TextView
    private lateinit var message: EditText
    private lateinit var newBranch: EditText
    private lateinit var cloneUrl: EditText
    private lateinit var cloneName: EditText
    private lateinit var out: TextView

    override fun build(root: LinearLayout) {
        root.addView(ui.section("Git"))
        branch = ui.label("", 15f, Theme.TEXT, true)
        root.addView(branch)
        root.addView(ui.row(ui.chip("Status") { git("status") }, ui.chip("Diff") { git("diff") }, ui.chip("Log") { git("log") }, ui.chip("Branches") { git("branch") },
            ui.chip("Pull") { git("pull") }, ui.chip("Push") { git("push") }, ui.chip("Init") { git("init") }, ui.chip("Stash") { git("stash") }))
        message = ui.field("commit message")
        root.addView(message)
        root.addView(ui.row(ui.chip("Commit all") { git("commit", "message" to message.text.toString()) }))
        newBranch = ui.field("new or existing branch")
        root.addView(newBranch)
        root.addView(ui.row(ui.chip("Switch / create") { git("branch", "ref" to newBranch.text.toString()) }))
        root.addView(ui.section("Clone"))
        cloneUrl = ui.field("https://github.com/owner/repo")
        cloneName = ui.field("folder name")
        root.addView(cloneUrl); root.addView(cloneName)
        root.addView(ui.row(ui.chip("Clone") { clone() }))
        out = ui.output(240); root.addView(out)
    }

    override fun onShow() {
        host.bg {
            val r = OrionStudio.run(host, JSONObject().put("action", "git").put("op", "status").put("project", host.project))
            host.post { branch.text = r.lineSequence().firstOrNull { it.startsWith("##") }?.removePrefix("## ")?.let { "⎇ $it" } ?: "No repository here — tap Init" }
        }
    }

    private fun git(op: String, vararg extra: Pair<String, String>) {
        out.text = "Running git $op…"
        host.bg {
            val a = JSONObject().put("action", "git").put("op", op).put("project", host.project)
            extra.forEach { a.put(it.first, it.second) }
            val r = OrionStudio.run(host, a)
            host.post { out.text = r.take(12000); onShow() }
        }
    }

    private fun clone() {
        out.text = "Cloning…"
        host.bg {
            val r = OrionStudio.run(host, JSONObject().put("action", "git_clone").put("url", cloneUrl.text.toString().trim()).put("name", cloneName.text.toString().trim()))
            host.post { out.text = r.take(6000) }
        }
    }
}
