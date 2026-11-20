package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider capability and model evaluation registry. Every score is measured (passively from real
 * turns, or by the evaluation probes); a dimension with no samples reads "unmeasured", never a guess.
 * Model choice and failover read this instead of guessing from model names.
 */
internal object OrionModelRegistry {
    enum class Dim(val label: String) {
        REASONING("general reasoning"), TOOL_CALLING("native tool calling"), STRUCTURED_ARGS("structured argument accuracy"),
        LONG_CONTEXT("long-context retrieval"), CODING("coding and debugging"), MULTI_STEP("multi-step completion"),
        FIRST_TOKEN("first-token latency"), ERROR_RECOVERY("error recovery")
    }

    /** Running mean of 0..1 observations, or milliseconds for FIRST_TOKEN. */
    data class Stat(val n: Int, val mean: Double)

    private const val PREFS = "orion_model_registry"
    private const val CAP = 200
    private val lock = Any()

    internal fun fold(old: Stat?, value: Double): Stat {
        val n = (old?.n ?: 0).coerceAtMost(CAP - 1)
        return Stat(n + 1, ((old?.mean ?: 0.0) * n + value) / (n + 1))
    }

    fun key(provider: String, model: String) = provider.trim() + "|" + model.trim()

    fun record(c: Context, provider: String, model: String, dim: Dim, value: Double) {
        if (provider.isBlank() || model.isBlank()) return
        runCatching {
            synchronized(lock) {
                val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val root = JSONObject(p.getString("all", "{}") ?: "{}")
                val k = key(provider, model)
                val m = root.optJSONObject(k) ?: JSONObject()
                val old = m.optJSONObject(dim.name)?.let { Stat(it.optInt("n"), it.optDouble("mean")) }
                val s = fold(old, if (dim == Dim.FIRST_TOKEN) value else value.coerceIn(0.0, 1.0))
                m.put(dim.name, JSONObject().put("n", s.n).put("mean", s.mean))
                root.put(k, m)
                // Keep the registry bounded: drop the least-sampled model beyond 60.
                if (root.length() > 60) root.keys().asSequence().minByOrNull { r -> root.getJSONObject(r).keys().asSequence().sumOf { d -> root.getJSONObject(r).getJSONObject(d).optInt("n") } }?.let { root.remove(it) }
                p.edit().putString("all", root.toString()).apply()
            }
        }
    }

    fun profile(c: Context, provider: String, model: String): Map<Dim, Stat> = runCatching {
        val m = JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("all", "{}") ?: "{}")
            .optJSONObject(key(provider, model)) ?: return emptyMap()
        Dim.values().mapNotNull { d -> m.optJSONObject(d.name)?.let { d to Stat(it.optInt("n"), it.optDouble("mean")) } }.toMap()
    }.getOrDefault(emptyMap())

    fun allProfiles(c: Context): Map<String, Map<Dim, Stat>> = runCatching {
        val root = JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("all", "{}") ?: "{}")
        root.keys().asSequence().associateWith { k ->
            val m = root.getJSONObject(k)
            Dim.values().mapNotNull { d -> m.optJSONObject(d.name)?.let { d to Stat(it.optInt("n"), it.optDouble("mean")) } }.toMap()
        }
    }.getOrDefault(emptyMap())

    /** Mean of the measured quality dimensions (latency excluded); null when nothing is measured. */
    fun quality(profile: Map<Dim, Stat>): Double? =
        profile.filterKeys { it != Dim.FIRST_TOKEN }.values.map { it.mean }.takeIf { it.isNotEmpty() }?.average()

    /** Best measured candidate for a dimension among [models] (needs >= minSamples), or null when nothing is measured. */
    fun best(profiles: Map<String, Map<Dim, Stat>>, dim: Dim, minSamples: Int = 3): String? =
        profiles.mapNotNull { (k, p) -> p[dim]?.takeIf { it.n >= minSamples }?.let { k to it.mean } }
            .let { list -> if (dim == Dim.FIRST_TOKEN) list.minByOrNull { it.second } else list.maxByOrNull { it.second } }?.first

    /** Measured quality for one model, or null below [minSamples] total quality samples. */
    fun qualityFor(profiles: Map<String, Map<Dim, Stat>>, provider: String, model: String, minSamples: Int = 3): Double? {
        val p = profiles[key(provider, model)] ?: return null
        val q = p.filterKeys { it != Dim.FIRST_TOKEN }
        if (q.values.sumOf { it.n } < minSamples) return null
        return quality(p)
    }

    fun firstTokenFor(profiles: Map<String, Map<Dim, Stat>>, provider: String, model: String, minSamples: Int = 3): Double? =
        profiles[key(provider, model)]?.get(Dim.FIRST_TOKEN)?.takeIf { it.n >= minSamples }?.mean

    // ---- passive measurement from real turns ----

    fun recordLatency(c: Context, provider: String, model: String, ms: Long) {
        if (ms > 0) record(c, provider, model, Dim.FIRST_TOKEN, ms.toDouble())
    }

    /** One native tool round: were the calls registered, with object arguments, and did they execute without failure. */
    fun recordToolRound(c: Context, label: String, calls: Int, validArgs: Int, failed: Int, priorRoundFailed: Boolean) {
        val (prov, model) = split(label) ?: return
        if (calls <= 0) return
        record(c, prov, model, Dim.TOOL_CALLING, if (validArgs > 0) 1.0 else 0.0)
        record(c, prov, model, Dim.STRUCTURED_ARGS, validArgs.toDouble() / calls)
        record(c, prov, model, Dim.MULTI_STEP, if (failed == 0) 1.0 else 0.0)
        if (priorRoundFailed) record(c, prov, model, Dim.ERROR_RECOVERY, if (failed == 0) 1.0 else 0.0)
    }

    internal fun split(label: String): Pair<String, String>? {
        val i = label.indexOf(" / ")
        return if (i <= 0) null else label.substring(0, i) to label.substring(i + 3)
    }

    // ---- active probes (scored by code, not by a model) ----

    data class Probe(val dim: Dim, val prompt: String, val score: (String) -> Double)

    private val filler = (1..140).joinToString(" ") { "Note $it: routine maintenance entry, nothing unusual recorded." }

    val probes: List<Probe> = listOf(
        Probe(Dim.REASONING, "A train leaves at 14:20 and the trip takes 2h 55m. Reply with only the arrival time as HH:MM (24h).") { if (it.contains("17:15")) 1.0 else 0.0 },
        Probe(Dim.STRUCTURED_ARGS, "Reply with only a JSON object {\"tool\":\"open_app\",\"args\":{\"name\":\"Spotify\",\"retries\":2}} and nothing else.") { scoreJson(it) },
        Probe(Dim.LONG_CONTEXT, filler + " Note 141: the vault code is 7419. " + filler + "\nWhat is the vault code? Reply with digits only.") { if (it.contains("7419")) 1.0 else 0.0 },
        Probe(Dim.CODING, "This Kotlin loop should sum 1..n but is wrong: `var s=0; for (i in 1 until n) s+=i`. Reply with only the corrected range expression.") { if (Regex("1\\s*\\.\\.\\s*n").containsMatchIn(it)) 1.0 else 0.0 }
    )

    internal fun scoreJson(text: String): Double {
        val body = text.substringAfter('{', "").let { if (it.isEmpty()) return 0.0 else "{$it" }.substringBeforeLast('}') + "}"
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return 0.0
        val a = o.optJSONObject("args") ?: return 0.25
        var s = 0.25
        if (o.optString("tool") == "open_app") s += 0.25
        if (a.optString("name") == "Spotify") s += 0.25
        if (a.optInt("retries", -1) == 2) s += 0.25
        return s
    }

    /** Run the probes against the currently selected chat model. Network use; call from a worker thread. */
    fun evaluate(c: Context): String {
        val nim = Nim(c)
        val label = Nim.lastChatModel()
        val out = StringBuilder()
        val results = mutableListOf<String>()
        for (p in probes) {
            val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", p.prompt))
            val t0 = System.currentTimeMillis()
            val reply = runCatching { nim.chat(msgs, false, 120, 0.0) }.getOrNull()
            val used = Nim.lastChatModel().ifBlank { label }
            val sp = split(used)
            if (reply == null || sp == null) { results += "${p.dim.label}: probe failed"; continue }
            val s = p.score(reply)
            record(c, sp.first, sp.second, p.dim, s)
            recordLatency(c, sp.first, sp.second, System.currentTimeMillis() - t0)
            results += "${p.dim.label}: ${"%.0f".format(s * 100)}%"
        }
        return out.append("EVALUATION of ").append(Nim.lastChatModel().ifBlank { "current model" }).append(": ").append(results.joinToString("; "))
            .append("\n(latency here is total reply time on a short prompt, recorded as a proxy.)").toString()
    }

    fun report(c: Context): String {
        val all = allProfiles(c)
        if (all.isEmpty()) return "MODEL REGISTRY: nothing measured yet. Run brain {action:\"evaluate\"} or just work; real turns feed it."
        val sb = StringBuilder("MODEL REGISTRY (measured only; n = samples)\n")
        for ((k, p) in all.entries.sortedByDescending { quality(it.value) ?: 0.0 }.take(12)) {
            sb.append("- ").append(k.replace('|', ' ')).append(": ")
            sb.append(Dim.values().joinToString("; ") { d ->
                val s = p[d]
                d.label + " " + when {
                    s == null -> "unmeasured"
                    d == Dim.FIRST_TOKEN -> "${s.mean.toLong()}ms (n=${s.n})"
                    else -> "${"%.0f".format(s.mean * 100)}% (n=${s.n})"
                }
            }).append('\n')
        }
        return sb.toString().trim()
    }
}
