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
    data class Plugin(
        val id: String, val version: String, val description: String, val tools: List<Tool>,
        val permissions: List<String> = emptyList(), val requires: List<String> = emptyList(),
        val onInstall: String = "", val onRemove: String = "", val source: String = ""
    )
    /** Capabilities a plugin may ask for. Granted as a set; a plugin cannot run until its owner grants them. */
    val knownPermissions = setOf("files.read", "files.write", "network", "shell")

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
        val perms = (o.optJSONArray("permissions") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } }
        perms.firstOrNull { it !in knownPermissions }?.let { return null to "unknown permission '$it'. Known: ${knownPermissions.sorted().joinToString()}." }
        val req = (o.optJSONArray("requires") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } }
        req.firstOrNull { !Regex("^[A-Za-z0-9._+-]{1,32}$").matches(it) }?.let { return null to "requires entry '$it' must be a plain program name." }
        val onInstall = o.optString("on_install").take(800); val onRemove = o.optString("on_remove").take(800)
        return Plugin(id, o.optString("version", "0.0.0").take(20), o.optString("description").take(200), tools, perms.distinct(), req.distinct().take(8), onInstall, onRemove, o.optString("homepage").take(200)) to ""
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

    /**
     * Installs a manifest. [trusted] manifests (the built-in catalog, or text the owner or Orion wrote locally) are granted
     * their permissions at once; manifests downloaded from a URL stay ungranted until `grant`, so third-party commands
     * never run unreviewed.
     */
    fun install(c: Context, manifest: String, trusted: Boolean = true): String {
        val (plugin, why) = parse(manifest)
        if (plugin == null) return "error: $why"
        val s = store(c)
        if (!s.has(plugin.id) && s.length() >= MAX_PLUGINS) return "error: $MAX_PLUGINS plugins is the limit; remove one first."
        s.put(plugin.id, JSONObject().put("m", manifest).put("on", true).put("granted", trusted || plugin.permissions.isEmpty()))
        save(c, s)
        val status = if (trusted || plugin.permissions.isEmpty()) "ready" else "needs review: permissions ${plugin.permissions.joinToString()}. Show the owner what it does, then call plugin {action:\"grant\", id:\"${plugin.id}\"}."
        val hook = if (trusted && plugin.onInstall.isNotBlank()) "\non_install: " + ShellRunner.run(c, plugin.onInstall, OrionUbuntu.where(c)).take(300) else ""
        return "installed ${plugin.id} ${plugin.version} [$status] with tools: " + plugin.tools.joinToString { it.name } + ". Call plugin {action:\"run\", id:\"${plugin.id}\", tool:\"<name>\", args:{…}}." + hook
    }

    fun installFromUrl(c: Context, url: String): String {
        val u = OrionPlaybooks.rawUrl(url)
        if (!u.startsWith("https://raw.githubusercontent.com/")) return "error: give a GitHub file link to a plugin.json."
        val text = runCatching {
            (java.net.URL(u).openConnection() as java.net.HttpURLConnection).run { connectTimeout = 15000; readTimeout = 30000; inputStream.bufferedReader().use { it.readText().take(40_000) } }
        }.getOrElse { return "error: could not download that file (${it.message})." }
        return install(c, text, trusted = false)
    }

    fun setEnabled(c: Context, id: String, on: Boolean): String {
        val s = store(c); val e = s.optJSONObject(id) ?: return "error: no plugin '$id'."
        s.put(id, e.put("on", on)); save(c, s)
        return "$id is now ${if (on) "enabled" else "disabled"}."
    }

    fun isGranted(c: Context, id: String): Boolean = store(c).optJSONObject(id)?.optBoolean("granted", true) ?: false

    fun setGranted(c: Context, id: String, granted: Boolean): String {
        val s = store(c); val e = s.optJSONObject(id) ?: return "error: no plugin '$id'."
        s.put(id, e.put("granted", granted)); save(c, s)
        val perms = installed(c).firstOrNull { it.first.id == id }?.first?.permissions.orEmpty()
        return if (granted) "$id granted: ${perms.joinToString().ifBlank { "no special permissions" }}." else "$id permissions revoked; it cannot run until granted again."
    }

    /** Programs a plugin needs that the Linux runtime does not have yet. */
    fun missingRequirements(c: Context, p: Plugin): List<String> =
        p.requires.filter { ShellRunner.run(c, "command -v " + q(it) + " >/dev/null 2>&1 && echo ok || echo missing", OrionUbuntu.where(c)).trim().endsWith("missing") }

    fun remove(c: Context, id: String): String {
        installed(c).firstOrNull { it.first.id == id }?.first?.onRemove?.takeIf { it.isNotBlank() && isGranted(c, id) }?.let { runCatching { ShellRunner.run(c, it, OrionUbuntu.where(c)) } }
        return removeEntry(c, id)
    }

    private fun removeEntry(c: Context, id: String): String { val s = store(c); return if (s.remove(id) != null) { save(c, s); "removed $id." } else "error: no plugin '$id'." }

    fun list(c: Context): String {
        val all = installed(c)
        if (all.isEmpty()) return "No plugins installed. Install one with plugin {action:\"install\", manifest:\"<plugin.json text>\"} or {action:\"install_url\", url}."
        return all.joinToString("\n") { (p, on) -> "${p.id} ${p.version} [${if (on) "on" else "off"}${if (isGranted(c, p.id)) "" else ", needs grant"}]${if (p.permissions.isEmpty()) "" else " (" + p.permissions.joinToString() + ")"} — ${p.description.ifBlank { "no description" }}\n" + p.tools.joinToString("\n") { "  ${it.name}: ${it.description.ifBlank { it.command.take(60) }}" } }
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
        if (!isGranted(c, id)) return "error: plugin $id has not been granted ${entry.first.permissions.joinToString().ifBlank { "its permissions" }}. Review it, then call plugin {action:\"grant\", id:\"$id\"}."
        missingRequirements(c, entry.first).takeIf { it.isNotEmpty() }?.let { return "error: plugin $id needs ${it.joinToString()} in the Linux runtime. Install with: apt-get install -y ${it.joinToString(" ")}" }
        val t = entry.first.tools.firstOrNull { it.name == tool } ?: return "error: plugin $id has no tool '$tool'. Tools: " + entry.first.tools.joinToString { it.name }
        val (cmd, missing) = render(t, args)
        if (cmd == null) return "error: tool $tool needs the argument '$missing'."
        return ShellRunner.run(c, cmd, OrionUbuntu.where(c))
    }

    /** Vetted plugins that ship with Orion; installing one is a single call and needs no review. */
    val catalog: Map<String, String> = mapOf(
        "git-tools" to """{"id":"git-tools","version":"1.0.0","description":"Inspect and sync git repositories in the workspace","permissions":["files.read","shell"],"requires":["git"],"tools":[
            {"name":"status","description":"short git status","command":"cd /workspace/{project} && git status -sb","timeout_sec":30},
            {"name":"log","description":"recent commits","command":"cd /workspace/{project} && git log --oneline -n {count}","timeout_sec":30},
            {"name":"diff","description":"uncommitted changes, stat view","command":"cd /workspace/{project} && git diff --stat","timeout_sec":30}]}""",
        "python-tools" to """{"id":"python-tools","version":"1.0.0","description":"Run, test and lint Python projects","permissions":["files.write","shell"],"requires":["python3"],"tools":[
            {"name":"run","description":"run a script","command":"cd /workspace/{project} && python3 {file}","timeout_sec":300},
            {"name":"test","description":"run pytest","command":"cd /workspace/{project} && python3 -m pytest -q","timeout_sec":600},
            {"name":"pip_install","description":"install a package","command":"python3 -m pip install --break-system-packages {package}","timeout_sec":600}]}""",
        "node-tools" to """{"id":"node-tools","version":"1.0.0","description":"Run and test Node projects","permissions":["files.write","network","shell"],"requires":["node","npm"],"tools":[
            {"name":"run","description":"run a script file","command":"cd /workspace/{project} && node {file}","timeout_sec":300},
            {"name":"install","description":"npm install","command":"cd /workspace/{project} && npm install","timeout_sec":900},
            {"name":"test","description":"npm test","command":"cd /workspace/{project} && npm test --silent","timeout_sec":600}]}""",
        "project-tools" to """{"id":"project-tools","version":"1.0.0","description":"Look around a project quickly","permissions":["files.read","shell"],"tools":[
            {"name":"tree","description":"two-level folder tree","command":"cd /workspace/{project} && find . -maxdepth 2 -not -path '*/.git*' | sort | head -200","timeout_sec":30},
            {"name":"find_text","description":"search text in files","command":"cd /workspace/{project} && grep -rn --exclude-dir=.git --exclude-dir=node_modules {pattern} . | head -80","timeout_sec":60},
            {"name":"size","description":"largest files","command":"cd /workspace/{project} && du -ah . 2>/dev/null | sort -rh | head -20","timeout_sec":60}]}"""
    )

    fun catalogList(c: Context): String {
        val have = installed(c).map { it.first.id }.toSet()
        return catalog.entries.joinToString("\n") { (id, m) ->
            val p = parse(m).first
            "$id${if (id in have) " [installed]" else ""} — ${p?.description.orEmpty()} (" + p?.tools?.joinToString { it.name }.orEmpty() + ")"
        } + "\nInstall with plugin {action:\"install_builtin\", id}."
    }

    /** Entry point for the `plugin` tool. */
    fun execute(c: Context, a: JSONObject): String = when (a.optString("action").lowercase()) {
        "", "list" -> list(c)
        "install" -> install(c, a.optString("manifest"))
        "install_url" -> installFromUrl(c, a.optString("url"))
        "catalog" -> catalogList(c)
        "install_builtin" -> catalog[a.optString("id")]?.let { install(c, it, trusted = true) } ?: "error: no built-in plugin '${a.optString("id")}'. " + catalogList(c)
        "grant" -> setGranted(c, a.optString("id"), true)
        "revoke" -> setGranted(c, a.optString("id"), false)
        "enable" -> setEnabled(c, a.optString("id"), true)
        "disable" -> setEnabled(c, a.optString("id"), false)
        "remove" -> remove(c, a.optString("id"))
        "run" -> run(c, a.optString("id"), a.optString("tool"), a.optJSONObject("args") ?: JSONObject())
        else -> "error: action must be list, catalog, install, install_builtin, install_url, grant, revoke, enable, disable, remove or run."
    }
}
