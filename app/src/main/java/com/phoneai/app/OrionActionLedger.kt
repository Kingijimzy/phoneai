package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orion's memory of what he did, not just what he knows: scripts he ran, tools he forged, and how each fix
 * turned out. Survives restarts. Also an index of where credentials live (never the secrets themselves), so
 * he can find the key he used last time without anyone retyping it.
 */
internal object OrionActionLedger {
    data class Entry(val ts: Long, val kind: String, val name: String, val ok: Boolean, val summary: String)

    private const val PREFS = "orion_ledger"
    private const val MAX = 300
    private val scriptTools = setOf("python_exec", "dev_python", "dev_shell", "shell", "devspace", "termux_command")
    private val loggedTools = scriptTools + setOf("extension_forge", "extension_run", "a2a", "mesh")

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun kindFor(tool: String) = when {
        tool in scriptTools -> "script"
        tool == "extension_forge" || tool.startsWith("ext_") || tool == "extension_run" -> "forge"
        else -> "link"
    }

    fun shouldLog(tool: String) = tool in loggedTools || OrionForgedTools.isForged(tool)

    @Synchronized fun record(c: Context, kind: String, name: String, ok: Boolean, summary: String, now: Long = System.currentTimeMillis()) {
        val list = load(c).toMutableList()
        list += Entry(now, kind, name.take(60), ok, AgentPrivacyFilter.safeCommand(summary.replace('\n', ' ')).take(220))
        save(c, list.takeLast(MAX))
    }

    fun recent(c: Context, query: String, n: Int = 4): List<Entry> {
        val words = query.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length > 3 }.toSet()
        if (words.isEmpty()) return emptyList()
        return load(c).asReversed().filter { it.kind != "link" }
            .map { e -> e to words.count { w -> (e.name + " " + e.summary).lowercase().contains(w) } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(n).map { it.first }
    }

    fun section(c: Context, request: String): String {
        val r = recent(c, request)
        if (r.isEmpty()) return ""
        return "PAST ACTIONS (his own log, reuse before rewriting):\n" + r.joinToString("\n") {
            "- ${it.kind} ${it.name}: ${if (it.ok) "worked" else "failed"}: ${it.summary}"
        }
    }

    // --- forged-fix judging: counters restart each time a tool is forged again ---

    @Synchronized fun noteForge(c: Context, id: String) { sp(c).edit().putString("since_$id", "0,0").apply() }

    @Synchronized fun noteRun(c: Context, id: String, ok: Boolean) {
        val (o, f) = sinceForge(c, id)
        sp(c).edit().putString("since_$id", if (ok) "${o + 1},$f" else "$o,${f + 1}").apply()
    }

    fun sinceForge(c: Context, id: String): Pair<Int, Int> =
        sp(c).getString("since_$id", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: (0 to 0)

    enum class Judgement { UNTESTED, WORKED, FAILING, MIXED }

    fun judge(ok: Int, failed: Int) = when {
        ok == 0 && failed == 0 -> Judgement.UNTESTED
        failed >= 2 && ok == 0 -> Judgement.FAILING
        ok >= 2 && failed == 0 -> Judgement.WORKED
        else -> Judgement.MIXED
    }

    /** A forged tool is retired only on evidence since its latest forge, so a rebuilt tool gets a fair chance. */
    fun isRetired(c: Context, id: String): Boolean = sinceForge(c, id).let { (o, f) -> f >= 3 && o == 0 }

    fun judgementReport(c: Context): String = SelfExtendingExtensionStore(c).all().mapNotNull { e ->
        val (o, f) = sinceForge(c, e.id)
        judge(o, f).takeIf { it != Judgement.UNTESTED }?.let { "- ext_${e.id}: ${it.name.lowercase()} since last forge ($o ok / $f failed)" +
            if (it == Judgement.WORKED) "; keeping it" else if (it == Judgement.FAILING) "; rebuild it differently" else "" }
    }.joinToString("\n")

    // --- credential index: where things live, never the values ---

    private val secretShape = Regex("(?i)(ghp_|gho_|github_pat_|sk-[a-z0-9]|xox[abp]-|AKIA[0-9A-Z]|-----BEGIN|bearer\\s+[a-z0-9._-]{20,}|[a-f0-9]{40,})")

    fun looksSecret(s: String) = secretShape.containsMatchIn(s)

    @Synchronized fun vaultAdd(c: Context, label: String, where: String): String {
        if (label.isBlank() || where.isBlank()) return "error: vault needs label and where"
        if (looksSecret(label) || looksSecret(where) || where.length > 160) return "error: that looks like a secret value. Store only where it lives (e.g. 'GitHub token: Settings > Connections > GitHub'), never the value."
        val arr = JSONObject(sp(c).getString("vault", "{}") ?: "{}")
        arr.put(label.trim().take(60), where.trim())
        sp(c).edit().putString("vault", arr.toString()).apply()
        return "ok: noted where '${label.trim()}' lives"
    }

    fun vault(c: Context): List<Pair<String, String>> =
        runCatching { JSONObject(sp(c).getString("vault", "{}") ?: "{}").let { o -> o.keys().asSequence().map { it to o.optString(it) }.toList() } }.getOrDefault(emptyList())

    /** Called after every tool call: logs scripts and forges, feeds the fix judge, remembers a new forge. */
    fun afterCall(c: Context, calledName: String, name: String, args: JSONObject, result: String) {
        if (!shouldLog(calledName) && !shouldLog(name)) return
        val ok = !OrionReflexion.looksFailed(result)
        val summary = when {
            name in scriptTools -> args.optString("command").ifBlank { args.optString("code").ifBlank { args.optString("script") } }.lineSequence().firstOrNull().orEmpty()
            else -> args.toString()
        }
        val forgeId = when {
            OrionForgedTools.isForged(calledName) -> calledName.removePrefix(OrionForgedTools.PREFIX)
            name == "extension_run" -> args.optString("id")
            name == "extension_forge" -> args.optString("id").ifBlank { args.optString("name") }
            else -> ""
        }
        record(c, kindFor(name), forgeId.ifBlank { name }, ok, summary.ifBlank { name })
        if (forgeId.isNotBlank()) {
            if (name == "extension_forge" && ok) noteForge(c, forgeId) else if (name != "extension_forge") noteRun(c, forgeId, ok)
        }
    }

    fun run(c: Context, a: JSONObject): String = when (a.optString("action")) {
        "ledger" -> recent(c, a.optString("query").ifBlank { "script forged tool" }, 8).joinToString("\n") {
            "${it.kind} ${it.name} ${if (it.ok) "ok" else "failed"}: ${it.summary}" }.ifBlank { "nothing logged for that yet" }
        "judge" -> judgementReport(c).ifBlank { "no forged tool has been run since its last forge" }
        "vault" -> a.optString("label").takeIf { it.isNotBlank() }?.let { vaultAdd(c, it, a.optString("where")) }
            ?: vault(c).joinToString("\n") { "${it.first}: ${it.second}" }.ifBlank { "vault index is empty" }
        else -> "ledger|judge|vault"
    }

    private fun load(c: Context): List<Entry> = runCatching {
        val arr = JSONArray(sp(c).getString("log", "[]"))
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Entry(it.optLong("t"), it.optString("k"), it.optString("n"), it.optBoolean("ok"), it.optString("s")) } }
    }.getOrDefault(emptyList())

    private fun save(c: Context, list: List<Entry>) {
        sp(c).edit().putString("log", JSONArray(list.map { JSONObject().put("t", it.ts).put("k", it.kind).put("n", it.name).put("ok", it.ok).put("s", it.summary) }).toString()).apply()
    }
}
