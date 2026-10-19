package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** A failure of one AI service. The next service can still be tried. */
class AiException(message: String, val rateLimited: Boolean = false) : RuntimeException(message)

/** Talks to the chosen AI service. If it fails, Orion tries the other services you saved keys for. */
class Nim(private val ctx: Context) {
    companion object {
        /** provider id -> time (ms) until which it is skipped (rate limited or bad key) */
        val cooldown = ConcurrentHashMap<String, Long>()

        /** provider id -> time of the last request (used to space requests out) */
        private val lastCall = ConcurrentHashMap<String, Long>()

        /** provider id -> times of requests in the last minute (so we slow down BEFORE a limit is hit) */
        private val hits = ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<Long>>()

        fun budget(id: String): Int = when (id) {
            "gemini" -> 9
            "groq", "cerebras", "mistral" -> 24
            "openrouter" -> 14
            "local" -> 1000
            else -> 18
        }

        fun usedLastMinute(id: String): Int {
            val q = hits[id] ?: return 0
            val cut = System.currentTimeMillis() - 60000L
            while (true) {
                val h = q.peekFirst() ?: break
                if (h < cut) q.pollFirst() else break
            }
            return q.size
        }

        private fun noteHit(id: String) {
            hits.getOrPut(id) { java.util.concurrent.ConcurrentLinkedDeque() }.addLast(System.currentTimeMillis())
        }

        /** Plain-words summary of every saved key: requests used this minute and any cool-down. */
        fun status(c: android.content.Context): String {
            val sb = StringBuilder()
            val now = System.currentTimeMillis()
            for (p in Providers.all) {
                if (!Prefs.hasKey(c, p.id)) continue
                val cd = ((cooldown[p.id] ?: 0L) - now) / 1000
                sb.append(p.label).append(": ").append(usedLastMinute(p.id)).append(" of ").append(budget(p.id)).append(" this minute")
                if (cd > 0) sb.append(", resting ").append(cd).append(" seconds")
                sb.append(". ")
            }
            return if (sb.isEmpty()) "No AI keys saved yet." else sb.toString().trim()
        }
    }

    private val bad = listOf(
        "embed", "guard", "rerank", "reward", "clip", "parse", "safety", "retriev", "bge",
        "whisper", "tts", "asr", "nemoguard", "riva", "fastpitch", "diffusion", "stable",
        "flux", "sdxl", "bria", "cosmos", "grounding", "segment", "ocr", "detect", "nvclip",
        "image", "imagen", "veo", "live", "transcribe", "translate", "audio", "moderation", "orpheus"
    )
    private val visionKeys = listOf(
        "vision", "-vl", "vila", "pixtral", "llava", "paligemma", "multimodal",
        "llama-4", "gemma-3", "gemini", "claude", "gpt-4o", "gpt-4.1"
    )
    private val preferred = listOf(
        "llama-3.3-nemotron", "llama-3.3-70b", "llama-3.1-nemotron", "llama-3.1-70b",
        "llama-4", "qwen", "mistral-large", "mixtral", "llama-3.1-8b", "gemma", "mistral", "phi"
    )
    private val fallbacks: Map<String, Pair<List<String>, List<String>>> = mapOf(
        "gemini" to Pair(
            listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite"),
            listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite")
        ),
        "groq" to Pair(
            listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "openai/gpt-oss-120b", "openai/gpt-oss-20b"),
            listOf("meta-llama/llama-4-scout-17b-16e-instruct", "meta-llama/llama-4-maverick-17b-128e-instruct")
        ),
        "cerebras" to Pair(
            listOf("llama-3.3-70b", "llama3.1-8b", "gpt-oss-120b", "qwen-3-32b"),
            emptyList<String>()
        ),
        "openrouter" to Pair(
            listOf(
                "meta-llama/llama-3.3-70b-instruct:free", "mistralai/mistral-small-3.1-24b-instruct:free",
                "qwen/qwen-2.5-72b-instruct:free"
            ),
            listOf("google/gemma-3-27b-it:free", "qwen/qwen2.5-vl-72b-instruct:free", "mistralai/mistral-small-3.1-24b-instruct:free")
        ),
        "mistral" to Pair(
            listOf("mistral-small-latest", "open-mistral-nemo", "mistral-large-latest"),
            listOf("mistral-small-latest", "pixtral-12b-2409")
        ),
        "github" to Pair(
            listOf("openai/gpt-4o-mini", "openai/gpt-4.1-mini", "meta/Llama-3.3-70B-Instruct"),
            listOf("openai/gpt-4o-mini", "openai/gpt-4.1-mini")
        ),
        "openai" to Pair(
            listOf("gpt-4.1-mini", "gpt-4o-mini", "gpt-4.1-nano"),
            listOf("gpt-4.1-mini", "gpt-4o-mini")
        ),
        "anthropic" to Pair(
            listOf("claude-haiku-5-5", "claude-sonnet-5-5"),
            listOf("claude-sonnet-5-5", "claude-haiku-5-5")
        ),
        "deepseek" to Pair(listOf("deepseek-chat"), emptyList<String>())
    )

    private class Http(val code: Int, val body: String, val retryAfter: Int = -1)

    private fun pause(ms: Long) {
        var left = ms
        while (left > 0 && !cancelled) {
            val s = minOf(left, 250L)
            Thread.sleep(s)
            left -= s
        }
    }

    /** Spaces out requests so free tiers (a few per minute) are not hit in a burst. */
    private fun pace(p: Provider) {
        val gap = when (p.id) {
            "gemini" -> 4500L
            "groq", "cerebras", "mistral" -> 2200L
            "openrouter" -> 3000L
            "github" -> 4000L
            "local" -> 0L
            else -> 800L
        }
        val wait = (lastCall[p.id] ?: 0L) + gap - System.currentTimeMillis()
        if (wait > 0) pause(wait)
        lastCall[p.id] = System.currentTimeMillis()
    }

    /** Called with a short progress message, from the worker thread. */
    @Volatile
    var onStatus: ((String) -> Unit)? = null

    @Volatile
    private var cancelled = false

    @Volatile
    private var active: HttpURLConnection? = null

    fun resume() {
        cancelled = false
    }

    /** Aborts the request that is in flight. */
    fun cancel() {
        cancelled = true
        try {
            active?.disconnect()
        } catch (e: Exception) {
        }
    }

    private fun request(p: Provider, method: String, url: String, body: String?, key: String, readMs0: Int = 45000): Http {
        val readMs = if (p.id == "local") 240000 else readMs0
        val conn = URL(url).openConnection() as HttpURLConnection
        active = conn
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = if (p.noKey) 180000 else readMs
            if (p.anthropicStyle) {
                conn.setRequestProperty("x-api-key", key)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
            } else {
                conn.setRequestProperty("Authorization", "Bearer $key")
            }
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val ra = conn.getHeaderField("Retry-After")?.trim()?.toDoubleOrNull()?.toInt() ?: -1
            return Http(code, text, ra)
        } finally {
            conn.disconnect()
        }
    }

    private fun keyBad(r: Http): Boolean =
        r.code == 401 || r.code == 403 ||
            (r.code == 400 && (r.body.contains("API key", true) || r.body.contains("api_key", true)))

    /** Every model id this provider's key can see. Throws with a readable message on failure. */
    fun allModels(p: Provider): List<String> {
        val key = Prefs.key(ctx, p.id)
        if (key.isEmpty()) throw IllegalStateException("No ${p.label} key saved.")
        val url = p.listUrl ?: (p.base + "/models")
        val r = try {
            request(p, "GET", url, null, key, 25000)
        } catch (e: java.io.IOException) {
            throw RuntimeException("Cannot reach ${p.label}. Check your internet connection. (${e.javaClass.simpleName})")
        }
        if (keyBad(r)) throw RuntimeException("${p.label} rejected the key (${r.code}). Make a new key and save it again.")
        if (r.code !in 200..299) throw RuntimeException("Could not list models: HTTP ${r.code} ${r.body.take(120)}")
        val trimmed = r.body.trim()
        val arr: JSONArray = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONObject(trimmed).optJSONArray("data") ?: return emptyList()
        }
        val ids = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            ids.add(o.optString("id").removePrefix("models/"))
        }
        return ids.filter { it.isNotBlank() }.sorted()
    }

    fun isVision(id: String): Boolean = visionKeys.any { id.lowercase().contains(it) }

    private fun candidates(p: Provider, vision: Boolean): List<String> {
        // In quota-saver mode, never spend an extra network request listing models.
        // Use the provider's small known fallback list instead.
        val ids = if (Prefs.quotaSaver(ctx)) emptyList() else try {
            allModels(p)
        } catch (e: Exception) {
            emptyList()
        }
        val ok = ids.filter { id -> bad.none { id.lowercase().contains(it) } }
        val out = ArrayList<String>()
        val fb: Pair<List<String>, List<String>> = fallbacks[p.id] ?: Pair(emptyList<String>(), emptyList<String>())
        when (p.id) {
            "gemini" -> {
                val g = ok.filter { it.lowercase().contains("gemini") }
                out.addAll(g.filter { it.contains("flash") && !it.contains("lite") })
                out.addAll(g.filter { it.contains("lite") })
                out.addAll(if (vision) fb.second else fb.first)
            }
            "openrouter" -> {
                val free = ok.filter { it.endsWith(":free") }
                out.addAll(if (vision) free.filter { isVision(it) } else free)
                out.addAll(if (vision) fb.second else fb.first)
            }
            "anthropic", "openai", "github", "mistral", "deepseek", "cerebras" -> {
                out.addAll(if (vision) fb.second else fb.first)
            }
            else -> {
                val vis = ok.filter { isVision(it) }
                if (vision) {
                    out.addAll(vis)
                } else {
                    val txt = ok.filter { it !in vis }
                    for (pref in preferred) {
                        for (id in txt) {
                            if (id.lowercase().contains(pref) && id !in out) out.add(id)
                        }
                    }
                    for (id in txt) {
                        if (id !in out) out.add(id)
                    }
                }
                out.addAll(if (vision) fb.second else fb.first)
            }
        }
        return out.distinct()
    }

    /** Converts OpenAI-style messages to Anthropic's format. */
    private fun anthropicBody(model: String, messages: JSONArray, tokens: Int, temperature: Double): String {
        val system = StringBuilder()
        val out = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = m.optString("role")
            val c = m.opt("content")
            if (role == "system") {
                if (system.isNotEmpty()) system.append("\n")
                system.append(c?.toString() ?: "")
                continue
            }
            val content: Any = if (c is JSONArray) {
                val parts = JSONArray()
                for (j in 0 until c.length()) {
                    val part = c.getJSONObject(j)
                    if (part.optString("type") == "image_url") {
                        val url = part.getJSONObject("image_url").optString("url")
                        val data = url.substringAfter("base64,", "")
                        parts.put(
                            JSONObject().put("type", "image").put(
                                "source",
                                JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", data)
                            )
                        )
                    } else {
                        parts.put(JSONObject().put("type", "text").put("text", part.optString("text")))
                    }
                }
                parts
            } else {
                c?.toString() ?: ""
            }
            // Anthropic wants strictly alternating roles; merge neighbours with the same role.
            val last = if (out.length() > 0) out.getJSONObject(out.length() - 1) else null
            if (last != null && last.optString("role") == role && content is String && last.opt("content") is String) {
                last.put("content", last.getString("content") + "\n" + content)
            } else {
                out.put(JSONObject().put("role", role).put("content", content))
            }
        }
        val o = JSONObject().put("model", model).put("max_tokens", tokens).put("messages", out)
        if (system.isNotEmpty()) o.put("system", system.toString())
        o.put("temperature", temperature)
        return o.toString()
    }

    private fun extract(p: Provider, body: String): String = try {
        if (p.anthropicStyle) {
            val arr = JSONObject(body).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until arr.length()) sb.append(arr.getJSONObject(i).optString("text", ""))
            sb.toString()
        } else {
            JSONObject(body).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "")
        }
    } catch (e: Exception) {
        ""
    }

    /** Asks the best chat or vision service; on failure, falls through to the others that have keys. */
    fun chat(messages: JSONArray, vision: Boolean, maxTokens: Int = 400, temperature: Double = 0.2): String {
        val first = Prefs.provider(ctx, vision)
        val order = ArrayList<Provider>()
        if (Prefs.hasKey(ctx, first.id)) order.add(first)
        if (Prefs.autoSwitch(ctx) || order.isEmpty()) {
            for (p in Providers.all) {
                if (p !== first && Prefs.hasKey(ctx, p.id) && (!vision || p.hasVision)) order.add(p)
            }
        }
        if (order.isEmpty()) {
            throw IllegalStateException("No AI key saved. Open Orion AI and add a key (Groq or Gemini are free).")
        }
        if (order.size > 1) {
            // Keep the user's selected provider first; sort only backups by recent use and speed.
            val primary = order.first()
            val rank = listOf(
                "groq", "cerebras", "gemini", "mistral", "github", "sambanova", "openrouter",
                "together", "fireworks", "huggingface", "deepseek", "openai", "anthropic", "local"
            )
            val nowMs = System.currentTimeMillis()
            val backups = order.drop(1).sortedBy {
                (lastCall[it.id] ?: 0L).coerceAtLeast(nowMs - 30000L) + rank.indexOf(it.id).coerceAtLeast(0) * 1500L
            }
            order.clear()
            order.add(primary)
            order.addAll(backups)
        }
        val errors = LinkedHashMap<String, String>()
        val start = System.currentTimeMillis()
        val quotaSaver = Prefs.quotaSaver(ctx)
        val maxRounds = if (quotaSaver) 1 else 4
        val providerLimit = if (quotaSaver) minOf(order.size, 2) else order.size
        var round = 0
        while (round < maxRounds) {
            round++
            if (cancelled) throw RuntimeException("Stopped.")
            val now = System.currentTimeMillis()
            val ready = order.take(providerLimit).filter { (cooldown[it.id] ?: 0L) <= now && usedLastMinute(it.id) < budget(it.id) }
            var allRateLimited = true
            for ((idx, p) in ready.withIndex()) {
                if (cancelled) throw RuntimeException("Stopped.")
                try {
                    if (idx > 0 || p !== order[0]) onStatus?.invoke("Using ${p.label}...")
                    return chatOne(p, messages, vision, maxTokens, temperature, order.size > 1)
                } catch (e: AiException) {
                    errors[p.id] = e.message ?: p.label
                    if (!e.rateLimited) allRateLimited = false
                }
            }
            // Nothing worked. If everything was only busy, wait for the first one to come back.
            if (!allRateLimited || round >= maxRounds || System.currentTimeMillis() - start > 170000) break
            val soonest = order.minOf { cooldown[it.id] ?: 0L }
            val wait = (soonest - System.currentTimeMillis()).coerceIn(2000L, 40000L)
            var left = wait
            while (left > 0) {
                if (cancelled) throw RuntimeException("Stopped.")
                onStatus?.invoke("AI services are busy. Waiting ${left / 1000 + 1}s...")
                Thread.sleep(500)
                left -= 500
            }
        }
        throw RuntimeException(errors.values.joinToString("\n").ifEmpty { "All AI services are cooling down. Try again in a minute." })
    }

    private fun chatOne(
        p: Provider, messages: JSONArray, vision: Boolean, maxTokens: Int, temperature: Double, hasBackup: Boolean
    ): String {
        val key = Prefs.key(ctx, p.id)
        // Gemini models "think" first and that uses up the token budget, so leave room.
        val tokens = if (p.id == "gemini") maxOf(maxTokens * 4, 1024) else maxTokens
        val queue = mutableListOf(Prefs.model(ctx, p, vision))
        val tried = HashSet<String>()
        var refilled = false
        var lastNote = ""
        var attempts = 0
        var rateRetries = 0
        val maxAttempts = if (Prefs.quotaSaver(ctx)) {
            if (hasBackup) 2 else 4
        } else {
            if (hasBackup) 4 else 12
        }
        val start = System.currentTimeMillis()
        while (queue.isNotEmpty() && attempts < maxAttempts && System.currentTimeMillis() - start < 120000) {
            if (cancelled) throw RuntimeException("Stopped.")
            val model = queue.removeAt(0)
            if (!tried.add(model)) continue
            attempts++
            if (usedLastMinute(p.id) >= budget(p.id)) {
                throw AiException("${p.label}: local request budget reached.", true)
            }
            pace(p)
            if (cancelled) throw RuntimeException("Stopped.")
            noteHit(p.id)
            onStatus?.invoke(p.label + ": asking " + model.substringAfterLast('/') + "...")
            val url: String
            val payload: String
            if (p.anthropicStyle) {
                url = p.base + "/messages"
                payload = anthropicBody(model, messages, tokens, temperature)
            } else {
                url = p.base + "/chat/completions"
                payload = JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("temperature", temperature)
                    .put("max_tokens", tokens)
                    .toString()
            }
            val r: Http? = try {
                request(p, "POST", url, payload, key)
            } catch (e: java.net.SocketTimeoutException) {
                null
            } catch (e: java.io.IOException) {
                if (cancelled) throw RuntimeException("Stopped.")
                cooldown[p.id] = System.currentTimeMillis() + (if (p.noKey) 30000L else 8000L)
                throw AiException(
                    if (p.noKey) "${p.label}: not running. In Termux run: ollama serve"
                    else "${p.label}: cannot connect (${e.javaClass.simpleName}). Check internet."
                )
            }
            if (r == null) {
                lastNote = "$model -> too slow"
                if (Prefs.quotaSaver(ctx)) {
                    // A timeout is not evidence that the model name is invalid. Avoid burning
                    // the remaining free quota trying several models after a slow response.
                    throw AiException(p.label + ": request timed out. Retry later or use the one configured backup.")
                }
                if (!refilled) {
                    refilled = true
                    queue.addAll(candidates(p, vision))
                }
                continue
            }
            if (keyBad(r)) throw AiException("${p.label}: key rejected (${r.code}). Save a new key.")
            if (r.code == 429) {
                val wait = if (r.retryAfter in 1..60) r.retryAfter else 10
                val canWait = !Prefs.quotaSaver(ctx) && !hasBackup && rateRetries < 2 && wait <= 30
                if (canWait) {
                    rateRetries++
                    onStatus?.invoke("${p.label} is busy. Waiting ${wait}s...")
                    pause(wait * 1000L)
                    tried.remove(model)
                    attempts--
                    queue.add(0, model)
                    continue
                }
                cooldown[p.id] = System.currentTimeMillis() + wait.coerceIn(8, 60) * 1000L
                throw AiException("${p.label}: free limit reached (429).", true)
            }
            if (r.code == 402) throw AiException("${p.label}: out of credit (402).")
            val modelProblem = r.code == 404 || r.code == 410 || r.code == 422 ||
                (r.code == 400 && r.body.isNotEmpty())
            if (modelProblem) {
                lastNote = "$model -> ${r.code} ${r.body.replace("\n", " ").take(100)}"
                if (!refilled) {
                    refilled = true
                    queue.addAll(candidates(p, vision))
                }
                continue
            }
            if (r.code !in 200..299) throw AiException("${p.label}: error ${r.code} ${r.body.take(120)}")
            val content = extract(p, r.body)
            if (content.isBlank() || content == "null") {
                lastNote = "$model -> empty reply"
                if (Prefs.quotaSaver(ctx)) {
                    throw AiException(p.label + ": returned an empty response. No repeated model retries were made.")
                }
                if (!refilled) {
                    refilled = true
                    queue.addAll(candidates(p, vision))
                }
                continue
            }
            Prefs.setModel(ctx, p, vision, model)
            return content
        }
        val kind = if (vision) "vision" else "chat"
        throw AiException("${p.label}: no $kind model worked. Last: $lastNote")
    }
}
