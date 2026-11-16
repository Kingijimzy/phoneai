package com.phoneai.app

import android.widget.LinearLayout
import android.widget.TextView

/** Runtime manager: Ubuntu is the primary development environment, Alpine the alternative. */
internal class StudioRuntimePage(host: StudioActivity) : StudioPage(host) {
    override val id = "runtime"
    override val title = "Runtime"
    private lateinit var status: LinearLayout
    private lateinit var out: TextView

    override fun build(root: LinearLayout) {
        root.addView(ui.section("Runtime manager"))
        status = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        root.addView(status)
        root.addView(ui.section("Toolchains"))
        root.addView(ui.row(
            ui.chip("python") { install("python") }, ui.chip("node") { install("node") }, ui.chip("go") { install("go") },
            ui.chip("rust") { install("rust") }, ui.chip("java") { install("java") }, ui.chip("c / c++") { install("c") }
        ))
        root.addView(ui.row(ui.chip("Installed tools") { act("toolchain_list") }, ui.chip("Install language servers") { act("lsp_install", "lang" to "python") }))
        out = ui.output(160)
        root.addView(out)
    }

    override fun onShow() = render()

    private fun render() {
        status.removeAllViews()
        val choice = OrionUbuntu.choice(host)
        val ubuntu = OrionUbuntu.isInstalled(host)
        val active = OrionUbuntu.where(host)
        fun card(name: String, role: String, ready: Boolean, key: String, install: () -> Unit) {
            val c = ui.card()
            c.addView(ui.label("$name  ·  $role", 15f, Theme.TEXT, true))
            c.addView(ui.label((if (ready) "Installed" else "Not installed") + if (active == key) "  ·  ACTIVE" else "", 12f, if (active == key) Theme.OK else Theme.MUTED))
            val actions = ArrayList<android.view.View>()
            if (!ready) actions += ui.chip("Install") { install() }
            if (ready && choice != key) actions += ui.chip("Make primary") { OrionUbuntu.setChoice(host, key); render() }
            c.addView(ui.row(*actions.toTypedArray()))
            status.addView(c)
        }
        card("Ubuntu 24.04", "primary development environment (apt)", ubuntu, "ubuntu") { act("ubuntu_install") }
        card("Alpine 3.22", "alternative (apk)", LinuxRuntimeInstaller(host).isReady(LinuxRuntimePolicy.productionManifest), "linux") { alpine() }
        status.addView(ui.row(ui.chip("Auto", choice == "auto") { OrionUbuntu.setChoice(host, "auto"); render() },
            ui.chip("Ubuntu only", choice == "ubuntu") { OrionUbuntu.setChoice(host, "ubuntu"); render() },
            ui.chip("Alpine only", choice == "linux") { OrionUbuntu.setChoice(host, "linux"); render() }))
    }

    private fun alpine() {
        out.text = "Preparing Alpine…"
        host.bg {
            val result = ShellRunner.run(host, "cat /etc/alpine-release; uname -m", "linux")
            host.post { out.text = result.take(2000); render() }
        }
    }

    private fun install(name: String) = act("toolchain_install", "name" to name)

    private fun act(action: String, vararg extra: Pair<String, String>) {
        out.text = "Running $action…"
        host.bg {
            val args = org.json.JSONObject().put("action", action).put("project", host.project)
            extra.forEach { args.put(it.first, it.second) }
            val result = OrionStudio.run(host, args)
            host.post { out.text = result.take(8000); render() }
        }
    }
}
