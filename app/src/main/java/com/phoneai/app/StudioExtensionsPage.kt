package com.phoneai.app

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** Extension manager: install, update, enable, disable and remove; every extension also has its own detail page. */
internal class StudioExtensionsPage(host: StudioActivity) : StudioPage(host) {
    override val id = "extensions"
    override val title = "Extensions"
    private lateinit var list: LinearLayout
    private lateinit var prometheus: LinearLayout
    private lateinit var search: EditText
    private lateinit var out: TextView

    override fun build(root: LinearLayout) {
        root.addView(ui.section("Extension manager"))
        search = ui.field("search Open VSX · npm · MCP registry")
        root.addView(search)
        root.addView(ui.row(ui.chip("VS Code") { discover("openvsx") }, ui.chip("npm") { discover("npm") }, ui.chip("MCP servers") { discover("mcp") }))
        out = ui.output(100)
        out.visibility = android.view.View.GONE
        root.addView(out)
        list = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(ui.section("Prometheus — Orion-generated extensions"))
        prometheus = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        root.addView(prometheus)
    }

    override fun onShow() { render(); renderPrometheus() }

    private fun render() {
        list.removeAllViews()
        val all = OrionStudio.catalog
        val custom = OrionStudio.registryList(host).lines().mapNotNull { l -> l.substring(2).substringBefore(' ').takeIf { it.isNotBlank() && all.none { c -> c.id == it } } }
            .mapNotNull { OrionStudio.registryEntry(host, it) }
        val entries = all + custom
        val installed = entries.filter { OrionStudio.isInstalledEntry(host, it.id) }
        val available = entries.filter { !OrionStudio.isInstalledEntry(host, it.id) }
        list.addView(ui.section("Installed (${installed.size})"))
        if (installed.isEmpty()) list.addView(ui.label("Nothing installed yet.", 12f, Theme.MUTED))
        installed.forEach { list.addView(card(it, true)) }
        list.addView(ui.section("Available (${available.size})"))
        available.forEach { list.addView(card(it, false)) }
    }

    private fun card(e: OrionStudio.Entry, installed: Boolean): LinearLayout {
        val c = ui.card()
        val enabled = OrionStudio.isEnabled(host, e.id)
        val head = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(ui.label(e.name, 14f, Theme.TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ui.badge(e.kind.name, Theme.MUTED))
        c.addView(head)
        c.addView(ui.label(e.about, 12f, Theme.MUTED))
        if (installed) c.addView(ui.label(if (enabled) "Enabled" else "Disabled", 11f, if (enabled) Theme.OK else Theme.WARN))
        val actions = ArrayList<android.view.View>()
        if (!installed) actions += ui.chip("Install") { act("registry_install", e.id) }
        else {
            actions += ui.chip(if (enabled) "Disable" else "Enable") { act(if (enabled) "registry_disable" else "registry_enable", e.id) }
            actions += ui.chip("Update") { act("registry_update", e.id) }
            actions += ui.chip("Remove") { act("registry_remove", e.id) }
        }
        actions += ui.chip("Open page") { host.push(detail(e)) }
        c.addView(ui.row(*actions.toTypedArray()))
        return c
    }

    /** The extension's own page: what it is, how it installs and runs, and its controls. */
    private fun detail(e: OrionStudio.Entry): android.view.View {
        val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val installed = OrionStudio.isInstalledEntry(host, e.id)
        col.addView(ui.section(e.kind.name))
        col.addView(ui.label(e.name, 22f, Theme.TEXT, true))
        col.addView(ui.label(e.about, 13f, Theme.TEXT_SECONDARY))
        col.addView(ui.label("Status: " + if (!installed) "not installed" else if (OrionStudio.isEnabled(host, e.id)) "installed · enabled" else "installed · disabled", 12f, Theme.TEXT))
        if (e.install.isNotBlank()) col.addView(ui.label("Install: ${e.install}", 11f, Theme.MUTED))
        if (e.run.isNotBlank()) col.addView(ui.label("Run: ${e.run}", 11f, Theme.MUTED))
        val result = ui.output(160)
        val actions = ArrayList<android.view.View>()
        fun call(action: String, vararg extra: Pair<String, String>) {
            result.text = "Running $action…"
            host.bg {
                val a = JSONObject().put("action", action).put("id", e.id)
                extra.forEach { a.put(it.first, it.second) }
                val r = OrionStudio.run(host, a)
                host.post { result.text = r.take(8000) }
            }
        }
        if (!installed) actions += ui.chip("Install") { call("registry_install") }
        else {
            if (e.run.isNotBlank()) actions += ui.chip("Start") { call("registry_run") }
            if (e.kind == OrionStudio.Kind.MCP) actions += ui.chip("List tools") { call("mcp_tools") }
            actions += ui.chip("Update") { call("registry_update") }
        }
        col.addView(ui.row(*actions.toTypedArray()))
        col.addView(result)
        return col
    }

    private fun act(action: String, id: String) {
        out.visibility = android.view.View.VISIBLE
        out.text = "Running $action…"
        host.bg {
            val r = OrionStudio.run(host, JSONObject().put("action", action).put("id", id))
            host.post { out.text = r.take(6000); render() }
        }
    }

    private fun discover(source: String) {
        val q = search.text.toString().trim()
        if (q.isEmpty()) { host.toast("Type what to search for"); return }
        out.visibility = android.view.View.VISIBLE
        out.text = "Searching $source…"
        host.bg { val r = OrionStudio.discover(source, q); host.post { out.text = r } }
    }

    /** Prometheus: the lifecycle of extensions Orion generated for itself. */
    private fun renderPrometheus() {
        prometheus.removeAllViews()
        val generated = runCatching { SelfExtendingExtensionStore(host).all() }.getOrDefault(emptyList())
        if (generated.isEmpty()) { prometheus.addView(ui.label("Orion has not generated any extensions yet. Ask it in chat to build one.", 12f, Theme.MUTED)); return }
        generated.forEach { g ->
            val c = ui.card()
            c.addView(ui.label(g.displayName, 14f, Theme.TEXT, true))
            c.addView(ui.label(g.description, 12f, Theme.MUTED))
            c.addView(ui.label((if (g.verified) "verified" else "draft") + " · " + (if (g.enabled) "enabled" else "disabled") + " · " + g.runtime, 11f, if (g.enabled) Theme.OK else Theme.WARN))
            if (g.enabled) c.addView(ui.row(ui.chip("Disable") {
                runCatching { SelfExtendingExtensionStore(host).disable(g.id) }; renderPrometheus()
            }))
            prometheus.addView(c)
        }
    }
}
