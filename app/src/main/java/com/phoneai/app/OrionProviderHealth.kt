package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/** Persistent per-provider outcome record, so failover decisions and the capability registry see real history. */
internal object OrionProviderHealth {
    data class Health(val provider: String, val ok: Int, val failed: Int, val consecutive: Int, val lastOkAt: Long, val lastFailAt: Long, val lastError: String) {
        val state: String get() = when {
            consecutive >= 3 -> "DOWN"
            consecutive >= 1 -> "FLAKY"
            ok > 0 -> "HEALTHY"
            else -> "UNTESTED"
        }
    }

    private const val PREFS = "orion_provider_health"
    private val lock = Any()

    fun record(c: Context, provider: String, ok: Boolean, error: String, now: Long = System.currentTimeMillis()) {
        if (provider.isBlank()) return
        runCatching {
            synchronized(lock) {
                val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val all = JSONObject(p.getString("all", "{}") ?: "{}")
                val h = all.optJSONObject(provider) ?: JSONObject()
                if (ok) h.put("ok", h.optInt("ok") + 1).put("consecutive", 0).put("okAt", now)
                else h.put("failed", h.optInt("failed") + 1).put("consecutive", h.optInt("consecutive") + 1).put("failAt", now)
                    .put("err", error.replace(Regex("\\s+"), " ").take(120))
                all.put(provider, h)
                p.edit().putString("all", all.toString()).apply()
            }
        }
    }

    fun all(c: Context): Map<String, Health> = runCatching {
        val all = JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("all", "{}") ?: "{}")
        all.keys().asSequence().associateWith { k ->
            val h = all.getJSONObject(k)
            Health(k, h.optInt("ok"), h.optInt("failed"), h.optInt("consecutive"), h.optLong("okAt"), h.optLong("failAt"), h.optString("err"))
        }
    }.getOrDefault(emptyMap())

    /** Table of every provider: configured?, chosen model, chat-capable?, recent record. */
    fun report(c: Context): String {
        val health = all(c)
        val rows = Providers.all.map { p ->
            val configured = Prefs.hasKey(c, p.id) || p.noKey
            val model = Prefs.model(c, p, false)
            val h = health[p.id]
            Triple(p, configured, "${p.label}: ${if (configured) "key set" else "no key"}, model $model${if (ProviderModelCapabilityPolicy.isChatCapable(model)) "" else " (NOT a chat model)"}, ${h?.state ?: "UNTESTED"}" +
                (if (h != null && h.failed > 0) " (${h.ok} ok / ${h.failed} failed${if (h.lastError.isNotBlank()) ", last: ${h.lastError}" else ""})" else if (h != null) " (${h.ok} ok)" else ""))
        }
        val ready = rows.count { it.second }
        return "PROVIDERS: $ready of ${rows.size} configured.\n" + rows.filter { it.second }.joinToString("\n") { it.third } +
            (if (ready == 0) "\nNo provider has a key: add one in Settings." else if (ready == 1) "\nOnly one provider is configured, so there is nothing to fail over to." else "")
    }

    /** Offline self-test: repairs what can be repaired without spending quota (non-chat saved models), reports the rest. */
    fun selfTest(c: Context): String {
        val fixed = ArrayList<String>(); val problems = ArrayList<String>()
        Providers.all.filter { Prefs.hasKey(c, it.id) || it.noKey }.forEach { p ->
            val model = Prefs.model(c, p, false)
            if (!ProviderModelCapabilityPolicy.isChatCapable(model)) {
                if (ProviderModelCapabilityPolicy.mustEvict(model)) { Prefs.resetModel(c, p, false); fixed += "${p.label}: dropped saved non-chat model $model, using ${p.textModel}" }
                else problems += "${p.label}: model $model looks like a non-chat model; pick another in the model picker"
            }
        }
        all(c).values.filter { it.state == "DOWN" }.forEach { problems += "${Providers.find(it.provider)?.label ?: it.provider} is DOWN (${it.consecutive} failures in a row: ${it.lastError})" }
        val ready = Providers.all.count { Prefs.hasKey(c, it.id) || it.noKey }
        if (ready < 2) problems += "only $ready provider configured: failover has no second route"
        return "SELF-TEST: " + (if (fixed.isEmpty() && problems.isEmpty()) "all configured providers look usable." else "") +
            (if (fixed.isNotEmpty()) "\nFixed: " + fixed.joinToString("; ") else "") + (if (problems.isNotEmpty()) "\nNeeds attention: " + problems.joinToString("; ") else "")
    }
}
