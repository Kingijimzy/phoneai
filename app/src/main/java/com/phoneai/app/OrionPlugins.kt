package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plugins: installable bundles of command-backed tools, described by a small plugin.json manifest. They run through the
 * single `plugin` tool, so every call goes through Orion's central coordinator, tool policy and the same Linux runtime
 * as any shell command; the central brain lists enabled plugins in its context so Orion knows what it can call.
 *
 * Manifest: {"id":"git-tools","version":"1.0.0","description":"...","tools":[{"name":"log","description":"...",
 * "command":"cd /workspace/{project} && git log --oneline -n {count}","timeout_sec":60}]}
 */
internal object OrionPlugins {
    data class Tool(val name: String, val description: String, val command: String, val timeoutSec: Int)
    data class Plugin(val id: String, val version: String, val description: String, val tools: List<Tool>)

    private const val PREFS = "orion_plugins"
    private const val MAX_PLUGINS = 20
    private const val MAX_TOOLS = 12
    private val idPattern = Regex("^[a-z0-9][a-z0-9-]{1,31}$")
    private val toolPattern = Regex("^[a-z][a-z0-9_]{1,31}$")
    private val placeholder = Regex("\\{([a-z][a-z0-9_]{0,23})\\}")

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Validates a manifest; returns the plugin or a one-line reason it was refused. */
    fun parse(text: String): Pair<Plugin?, String> {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null to "manifest is not valid JSON."
        val id = o.optString("id")
        if (!idPattern.matches(id)) return null to "id must be 2-32 lowercase letters, digits or dashes."
        val arr = o.optJSONArray("tools") ?: return null to "manifest needs a tools list."
        if (arr.length() == 0 || arr.length() > MAX_TOOLS) return null to "a plugin needs 1-$MAX_TOOLS tools."
        val tools = (0 until arr.length()).map { i ->
            val t = arr.optJSONObject(i) ?: return null to "tool $i is not an object."
            val name = t.optString("name"); val cmd = t.optString("command")
            if (!toolPattern.matches(name)) return null to "tool name '$name' must be lowercase letters, digits or underscores."
            if (cmd.isBlank() || cmd.length > 800) return null to "tool '$name' needs a command of up to 800 characters."
            Tool(name, t.optString("description").take(160), cmd, t.optInt("timeout_sec", 120).coerceIn(5, 900))
        }
        if (tools.map { it.name }.toSet().size != tools.size) return null to "tool names must be unique."
        return Plugin(id, o.optString("version", "0.0.0").take(20), o.optString("description").take(200), tools) to ""
    }

    /** Fills {placeholders} with single-quoted, shell-safe values; null (with the missing name) if an argument is absent. */
    fun render(tool: Tool, args: JSONObject): Pair<String?, String> {
        var missing = ""
        val out = placeholder.replace(tool.command) { m ->
            val v = args.optString(m.groupValues[1], "")
            if (v.isEmpty() && !args.has(m.groupValues[1])) { missing = m.groupValues[1]; "" } else q(v.take(500))
        }
        return if (missing.isNotEmpty()) null to missing else out to ""
    }

    private fun store(c: Context): JSONObject = runCatching { JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("p", "{}")) }.getOrDefault(JSONObject())
    private fun save(c: Context, o: JSONObject) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("p", o.toString()).apply()

    fun installed(c: Context): List<Pair<Plugin, Boolean>> {
        val s = store(c)
        return s.keys().asSequence().toList().sorted().mapNotNull { id ->
            val e = s.getJSONObject(id)
            parse(e.optString("m")).first?.let { it to e.optBoolean("on", true) }
        }
    }

    fun install(c: Context, manifest: String): String {
        val (plugin, why) = parse(manifest)
        if (plugin == null) return "error: $why"
        val s = store(c)
        if (!s.has(plugin.id) && s.length() >= MAX_PLUGINS) return "error: $MAX_PLUGINS plugins is the limit; remove one first."
        s.put(plugin.id, JSONObject().put("m", manifest).put("on", true))
        save(c, s)
        return "installed ${plugin.id} ${plugin.version} with tools: " + plugin.tools.joinToString { it.name } + ". Call plugin {action:\"run\", id:\"${plugin.id}\", tool:\"<name>\", args:{…}}."
    }

    fun installFromUrl(c: Context, url: String): String {
        val u = OrionPlaybooks.rawUrl(url)
        if (!u.startsWith("https://raw.githubusercontent.com/")) return "error: give a GitHub file link to a plugin.json."
        val text = runCatching {
            (java.net.URL(u).openConnection() as java.net.HttpURLConnection).run { connectTimeout = 15000; readTimeout = 30000; inputStream.bufferedReader().use { it.readText().take(40_000) } }
        }.getOrElse { return "error: could not download that file (${it.message})." }
        return install(c, text)
    }

    fun setEnabled(c: Context, id: String, on: Boolean): String {
        val s = store(c); val e = s.optJSONObject(id) ?: return "error: no plugin '$id'."
        s.put(id, e.put("on", on)); save(c, s)
        return "$id is now ${if (on) "enabled" else "disabled"}."
    }

    fun remove(c: Context, id: String): String { val s = store(c); return if (s.remove(id) != null) { save(c, s); "removed $id." } else "error: no plugin '$id'." }

    fun list(c: Context): String {
        val all = installed(c)
        if (all.isEmpty()) return "No plugins installed. Install one with plugin {action:\"install\", manifest:\"<plugin.json text>\"} or {action:\"install_url\", url}."
        return all.joinToString("\n") { (p, on) -> "${p.id} ${p.version} [${if (on) "on" else "off"}] — ${p.description.ifBlank { "no description" }}\n" + p.tools.joinToString("\n") { "  ${it.name}: ${it.description.ifBlank { it.command.take(60) }}" } }
    }

    /** One compact block for the central brain: what enabled plugins offer right now. */
    fun promptBlock(c: Context): String {
        val on = installed(c).filter { it.second }.map { it.first }
        if (on.isEmpty()) return ""
        return "INSTALLED PLUGINS (call with plugin {action:\"run\", id, tool, args}; they run in the Linux runtime):\n" +
            on.joinToString("\n") { p -> "- ${p.id}: " + p.tools.joinToString("; ") { t -> t.name + "(" + placeholder.findAll(t.command).map { it.groupValues[1] }.distinct().joinToString(",") + ") " + t.description.take(60) } }.take(2500)
    }

    fun run(c: Context, id: String, tool: String, args: JSONObject): String {
        val entry = installed(c).firstOrNull { it.first.id == id } ?: return "error: no plugin '$id'. Installed: " + installed(c).joinToString { it.first.id }.ifBlank { "none" }
        if (!entry.second) return "error: plugin $id is disabled."
        val t = entry.first.tools.firstOrNull { it.name == tool } ?: return "error: plugin $id has no tool '$tool'. Tools: " + entry.first.tools.joinToString { it.name }
        val (cmd, missing) = render(t, args)
        if (cmd == null) return "error: tool $tool needs the argument '$missing'."
        return ShellRunner.run(c, cmd, OrionUbuntu.where(c))
    }

    /** Entry point for the `plugin` tool. */
    fun execute(c: Context, a: JSONObject): String = when (a.optString("action").lowercase()) {
        "", "list" -> list(c)
        "install" -> install(c, a.optString("manifest"))
        "install_url" -> installFromUrl(c, a.optString("url"))
        "enable" -> setEnabled(c, a.optString("id"), true)
        "disable" -> setEnabled(c, a.optString("id"), false)
        "remove" -> remove(c, a.optString("id"))
        "run" -> run(c, a.optString("id"), a.optString("tool"), a.optJSONObject("args") ?: JSONObject())
        else -> "error: action must be list, install, install_url, enable, disable, remove or run."
    }
}
