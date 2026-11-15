package com.phoneai.app

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-model context/output maxima. Static knowledge is only a starting point: limits learned from
 * the provider's own catalog (Gemini inputTokenLimit/outputTokenLimit, context_length,
 * max_model_len, max_output_tokens) and from HTTP 400 error text always win and are persisted.
 */
internal object OrionModelLimits {
    data class Limit(val context: Int?, val output: Int?)

    private val learned = ConcurrentHashMap<String, Limit>()

    private fun key(provider: String, model: String) = provider.lowercase() + "|" + model.lowercase().removePrefix("models/")

    fun learned(provider: String, model: String): Limit? = learned[key(provider, model)]

    fun record(provider: String, model: String, context: Int?, output: Int?, ctx: Context? = null) {
        if ((context ?: 0) <= 0 && (output ?: 0) <= 0) return
        val k = key(provider, model)
        val old = learned[k]
        val merged = Limit(
            context?.takeIf { it > 0 } ?: old?.context,
            output?.takeIf { it > 0 } ?: old?.output
        )
        learned[k] = merged
        if (ctx != null) try {
            ctx.getSharedPreferences("phoneai_model_limits", Context.MODE_PRIVATE).edit()
                .putString(k, "${merged.context ?: 0},${merged.output ?: 0}").apply()
        } catch (_: Exception) {}
    }

    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            for ((k, v) in ctx.getSharedPreferences("phoneai_model_limits", Context.MODE_PRIVATE).all) {
                val parts = (v as? String)?.split(",") ?: continue
                val c = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 }
                val o = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
                learned.putIfAbsent(k, Limit(c, o))
            }
        } catch (_: Exception) {}
    }

    internal fun clearForTest() = learned.clear()

    /** Reads limit fields out of one catalog entry (Gemini native or OpenAI-compatible variants). */
    fun parseCatalogEntry(o: JSONObject): Limit {
        fun int(vararg names: String): Int? {
            for (n in names) {
                val v = o.opt(n)
                val i = (v as? Number)?.toLong() ?: (v as? String)?.toLongOrNull()
                if (i != null && i > 0) return i.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            return null
        }
        val ctx = int("inputTokenLimit", "context_length", "context_window", "max_model_len", "max_context_length")
        val out = int("outputTokenLimit", "max_output_tokens", "max_completion_tokens")
            ?: o.optJSONObject("top_provider")?.optLong("max_completion_tokens", 0)?.takeIf { it > 0 }?.toInt()
        return Limit(ctx, out)
    }

    private val outputPatterns = listOf(
        Regex("""max_tokens[^0-9]{0,80}?(?:maximum|max|limit|at most|less than or equal to|<=)[^0-9]{0,20}(\d{3,8})""", RegexOption.IGNORE_CASE),
        Regex("""(?:maximum|max|limit)[^0-9]{0,40}?(\d{3,8})[^.]{0,40}?(?:completion|output|max_tokens)""", RegexOption.IGNORE_CASE),
        Regex("""max_tokens[^0-9]{0,40}(?:must be|should be)[^0-9]{0,30}(\d{3,8})""", RegexOption.IGNORE_CASE),
        Regex("""valid range of max_tokens is \[\d+,\s*(\d{3,8})]""", RegexOption.IGNORE_CASE)
    )
    private val contextPatterns = listOf(
        Regex("""maximum context length is (\d{3,8})""", RegexOption.IGNORE_CASE),
        Regex("""context (?:length|window)[^0-9]{0,40}(\d{4,8})""", RegexOption.IGNORE_CASE),
        Regex("""(?:supports|limit(?:ed)? to)[^0-9]{0,20}(\d{4,8})[^.]{0,20}tokens""", RegexOption.IGNORE_CASE)
    )

    /** Extracts a limit from a provider 400 body. Returns null when nothing is stated. */
    fun parseErrorText(body: String): Limit? {
        val text = body.replace("\\n", " ").replace("\n", " ")
        val out = outputPatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.toIntOrNull() }
        val ctx = contextPatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.toIntOrNull() }
        if (out == null && ctx == null) return null
        return Limit(ctx, out)
    }

    /** Best-known static maxima (family level). Runtime-learned values override these. */
    fun static(providerId: String, modelId: String): Limit? {
        val m = modelId.lowercase().removePrefix("models/")
        return when {
            m.contains("gemini-3") || m.contains("gemini-2.5") || m.contains("gemini-flash") ->
                Limit(1_048_576, 65_536)
            m.contains("deepseek-v4") -> Limit(1_000_000, 384_000)
            m.contains("deepseek-v3") || m.contains("deepseek-chat") || m.contains("deepseek-reasoner") ->
                Limit(128_000, 64_000)
            m.contains("nemotron") && (m.contains("-3") || m.contains("3.5") || m.contains("super") || m.contains("ultra")) ->
                Limit(1_000_000, 65_536)
            m.contains("nemotron") -> Limit(128_000, 32_768)
            else -> null
        }
    }

    fun resolve(providerId: String, modelId: String): Limit? {
        val l = learned(providerId, modelId)
        val s = static(providerId, modelId)
        if (l == null) return s
        return Limit(l.context ?: s?.context, l.output ?: s?.output)
    }
}
