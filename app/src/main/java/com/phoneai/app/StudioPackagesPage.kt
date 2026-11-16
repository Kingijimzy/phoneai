package com.phoneai.app

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Package manager: apt (Ubuntu), apk (Alpine), pip and npm in the active runtime. */
internal class StudioPackagesPage(host: StudioActivity) : StudioPage(host) {
    override val id = "packages"
    override val title = "Packages"
    private var manager = "auto"
    private lateinit var managers: LinearLayout
    private lateinit var name: EditText
    private lateinit var out: TextView

    override fun build(root: LinearLayout) {
        root.addView(ui.section("Package manager"))
        managers = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        root.addView(managers)
        name = ui.field("package name(s)")
        root.addView(name)
        root.addView(ui.row(
            ui.chip("Search") { go("search") }, ui.chip("Install") { go("install") }, ui.chip("Remove") { go("remove") },
            ui.chip("Installed") { go("list") }, ui.chip("Update all") { go("update") }
        ))
        out = ui.output(260)
        root.addView(out)
    }

    override fun onShow() = renderManagers()

    private fun renderManagers() {
        managers.removeAllViews()
        val system = if (OrionUbuntu.where(host) == "ubuntu") "apt" else "apk"
        managers.addView(ui.row(
            ui.chip("$system (system)", manager == "auto" || manager == system) { manager = "auto"; renderManagers() },
            ui.chip("pip", manager == "pip") { manager = "pip"; renderManagers() },
            ui.chip("npm", manager == "npm") { manager = "npm"; renderManagers() }
        ))
    }

    private fun go(op: String) {
        val names = name.text.toString().split(' ', ',').filter { it.isNotBlank() }
        out.text = "Running $op…"
        host.bg {
            val a = org.json.JSONObject().put("action", "packages").put("manager", manager).put("op", op)
            a.put("names", org.json.JSONArray(names))
            val result = OrionStudio.run(host, a)
            host.post { out.text = result.take(12000) }
        }
    }
}
