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
        /** Latest provider-reported quota headers, when that API exposes them. */
        private val providerQuota = ConcurrentHashMap<String, String>()
        /** Last confirmed provider/model per mode; response text is never stored here. */
        private val lastSuccess = ConcurrentHashMap<String, String>()
        /** Most recent failure per provider, kept only in memory for diagnostics. */
        private val lastFailure = ConcurrentHashMap<String, String>()

        fun budget(id: String): Int = when (id) {
            "gemini" -> 9
            "groq", "cerebras", "mistral" -> 24
            "openrouter" -> 14
            "nvidia" -> 18
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
                sb.append(p.label).append(": local usage counter ")
                    .append(usedLastMinute(p.id)).append("/").append(budget(p.id)).append(" requests in the last minute (not the provider's actual quota)")
                providerQuota[p.id]?.let { sb.append("; provider-reported quota: ").append(it) }
                if (cd > 0) sb.append(", cooling down ").append(cd).append(" seconds")
                sb.append(". ")
            }
            sb.append("\nSelected chat provider: ").append(Prefs.provider(c, false).label)
            sb.append("\nSelected screen-vision provider: ").append(Prefs.provider(c, true).label)
            lastSuccess["chat"]?.let { sb.append("\nLast successful chat: ").append(it) }
            lastSuccess["vision"]?.let { sb.append("\nLast successful screen-vision: ").append(it) }
            if (lastFailure.isNotEmpty()) {
                sb.append("\nRecent provider errors:")
                for (p in Providers.all) lastFailure[p.id]?.let {
                    sb.append("\n• ").append(p.label).append(": ").append(it.take(180))
                }
            }
            val hasKeys = Providers.all.any { Prefs.hasKey(c, it.id) }
            return if (!hasKeys && lastSuccess.isEmpty() && lastFailure.isEmpty()) {
                "No AI keys saved yet. Add a provider key in Settings."
            } else sb.toString().trim()
        }
    }

    private val bad = listOf(
        "embed", "guard", "rerank", "reward", "clip", "parse", "safety", "retriev", "bge",
        "whisper", "tts", "asr", "nemoguard", "riva", "fastpitch", "diffusion", "stable",
        "flux", "sdxl", "bria", "cosmos", "grounding", "segment", "ocr", "detect", "nvclip",
        "image", "imagen", "veo", "live", "transcribe", "translate", "audio", "moderation", "orpheus"
    )
    private val visionKeys = listOf(
        "vision", "-vl", "vila", "pixtral", "llava", "paligemma", "multimodal", "nemotron-nano-12b-v2-vl",
        "llama-4", "gemma-3", "gemini", "claude", "gpt-4o", "gpt-4.1"
    )
    private val preferred = listOf(
        "deepseek-v4.1-flash", "deepseek-ai/deepseek-v4.1-flash",
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
            // Free model IDs change frequently; prefer current published free endpoints, then older backups.
            listOf(
                "nvidia/nemotron-3.5-lightning:free",
                "nvidia/nemotron-3-ultra-550b-a55b:free",
                "meta-llama/llama-3.3-70b-instruct:free"
            ),
            listOf(
                "nvidia/nemotron-3.5-super-vl:free",
                "google/gemma-3-27b-it:free",
                "qwen/qwen2.5-vl-72b-instruct:free"
            )
        ),
        "mistral" to Pair(
            listOf("mistral-small-latest", "open-mistral-nemo", "mistral-large-latest"),
            listOf("mistral-small-latest", "pixtral-12b-2409")
        ),
        "openai" to Pair(
            listOf("gpt-4.1-mini", "gpt-4o-mini", "gpt-4.1-nano"),
            listOf("gpt-4.1-mini", "gpt-4o-mini")
        ),
        "anthropic" to Pair(
            listOf("claude-haiku-5-5", "claude-sonnet-5-5"),
            listOf("claude-sonnet-5-5", "claude-haiku-5-5")
        ),
        "deepseek" to Pair(listOf("deepseek-chat"), emptyList<String>()),
        "nvidia" to Pair(
            // Keep the currently published multimodal Nemotron first; use other text models only as fallbacks.
            listOf(
                "nvidia/nemotron-3.5-super-vl-120b-a12b",
                "deepseek-ai/deepseek-v4.1-flash",
                "nvidia/nemotron-3.5-lightning-30b-a3b",
                "nvidia/nemotron-3-super-120b-a12b",
                "meta/llama-3.3-70b-instruct"
            ),
            listOf(
                "nvidia/nemotron-3.5-super-vl-120b-a12b",
                "deepseek-ai/deepseek-v4.1-flash",
                "nvidia/nemotron-nano-12b-v2-vl",
                "meta/llama-3.2-11b-vision-instruct"
            )
        )
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
            "nvidia" -> 2500L
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
            // Capture real provider quota headers when available. Header names vary by provider,
            // so this is supplemental telemetry; the local throttle remains a conservative guard.
            val rq = conn.getHeaderField("x-ratelimit-remaining-requests")
                ?: conn.getHeaderField("X-RateLimit-Remaining-Requests")
            val lq = conn.getHeaderField("x-ratelimit-limit-requests")
                ?: conn.getHeaderField("X-RateLimit-Limit-Requests")
            val rt = conn.getHeaderField("x-ratelimit-remaining-tokens")
                ?: conn.getHeaderField("X-RateLimit-Remaining-Tokens")
            val lt = conn.getHeaderField("x-ratelimit-limit-tokens")
                ?: conn.getHeaderField("X-RateLimit-Limit-Tokens")
            val reset = conn.getHeaderField("x-ratelimit-reset-requests")
                ?: conn.getHeaderField("X-RateLimit-Reset-Requests")
            val quotaParts = mutableListOf<String>()
            if (!rq.isNullOrBlank() && !lq.isNullOrBlank()) quotaParts.add("$rq/$lq requests remain")
            if (!rt.isNullOrBlank() && !lt.isNullOrBlank()) quotaParts.add("$rt/$lt tokens remain")
            if (!reset.isNullOrBlank()) quotaParts.add("request reset $reset")
            if (quotaParts.isNotEmpty()) providerQuota[p.id] = quotaParts.joinToString(", ")
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
        // Normally quota-saver mode avoids the extra /models request. NVIDIA's catalog
        // retires model IDs frequently (410 Gone), so discover IDs currently exposed to this
        // key after a model failure even in quota-saver mode rather than retrying retired IDs.
        val ids = if (Prefs.quotaSaver(ctx) && p.id != "nvidia") emptyList() else try {
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
        // The local Ollama provider intentionally has no API key; allow it when explicitly selected.
        // A text-only selected provider must never be the first attempt for an image.
        // If it cannot accept vision requests, select a compatible saved provider instead.
        if ((Prefs.hasKey(ctx, first.id) || first.noKey) && (!vision || first.hasVision)) order.add(first)
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
                "groq", "cerebras", "gemini", "mistral", "sambanova", "openrouter", "nvidia",
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
        // One bounded cooldown wait is allowed only if every eligible provider returns 429.
        // Timeouts, empty responses, invalid models, and ordinary errors never trigger a blind second round.
        val maxRounds = 2
        val providerLimit = order.size
        var round = 0
        while (round < maxRounds) {
            round++
            if (cancelled) throw RuntimeException("Stopped.")
            val now = System.currentTimeMillis()
            val ready = order.take(providerLimit).filter { (cooldown[it.id] ?: 0L) <= now }
            var allRateLimited = true
            for ((idx, p) in ready.withIndex()) {
                if (cancelled) throw RuntimeException("Stopped.")
                try {
                    onStatus?.invoke("Trying ${p.label} (${idx + 1}/${ready.size})...")
                    val answer = chatOne(p, messages, vision, maxTokens, temperature, order.size > 1)
                    val mode = if (vision) "vision" else "chat"
                    lastSuccess[mode] = p.label + " / " + Prefs.model(ctx, p, vision)
                    lastFailure.remove(p.id)
                    return answer
                } catch (e: Exception) {
                    if (cancelled) throw RuntimeException("Stopped.")
                    errors[p.id] = e.message ?: p.label
                    lastFailure[p.id] = (e.message ?: "Unknown provider error").take(220)
                    if (e !is AiException || !e.rateLimited) allRateLimited = false
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
        throw RuntimeException(if (errors.isNotEmpty()) "Every eligible saved provider was tried: " + errors.entries.joinToString("; ") { (id, message) -> (Providers.find(id)?.label ?: id) + ": " + message } else "All saved providers are in cooldown or no saved key supports this request type. Check Settings → AI providers. The local usage counter is informational; actual limits are decided by each provider.")
    }

    private fun chatOne(
        p: Provider, messages: JSONArray, vision: Boolean, maxTokens: Int, temperature: Double, hasBackup: Boolean
    ): String {
        val key = Prefs.key(ctx, p.id)
        // Gemini needs room for its internal thinking. NVIDIA Nemotron 3.5 Super VL
        // reasons by default; short probes (e.g. max_tokens=16) can spend the entire
        // budget reasoning and return choices[0].message.content="" (finish_reason=length).
        // Disable thinking for reliable direct chat/vision replies and provide a safe minimum.
        val tokens = when (p.id) {
            "gemini" -> maxOf(maxTokens * 4, 1024)
            "nvidia" -> maxOf(maxTokens, 1024)
            else -> maxTokens
        }
        val queue = mutableListOf(Prefs.model(ctx, p, vision))
        val tried = HashSet<String>()
        var refilled = false
        var lastNote = ""
        var attempts = 0
        var rateRetries = 0
        // In quota-saver mode, allow one alternate model only after an invalid/retired model response.
        // Timeouts, empty replies, and 429s exit immediately, so this does not create retry fan-out.
        val maxAttempts = if (Prefs.quotaSaver(ctx)) {
            2
        } else {
            if (hasBackup) 4 else 12
        }
        val start = System.currentTimeMillis()
        val totalTimeoutMs = if (p.id == "nvidia") 240000L else 120000L
        while (queue.isNotEmpty() && attempts < maxAttempts && System.currentTimeMillis() - start < totalTimeoutMs) {
            if (cancelled) throw RuntimeException("Stopped.")
            val model = queue.removeAt(0)
            if (!tried.add(model)) continue
            attempts++
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
                val requestBody = JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("temperature", temperature)
                    .put("max_tokens", tokens)
                if (p.id == "nvidia" && model.equals("nvidia/nemotron-3.5-super-vl-120b-a12b", ignoreCase = true)) {
                    // This template flag is documented for Nemotron 3.5 Super VL only.
                    requestBody.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                } else if (p.id == "nvidia" && model.lowercase().contains("deepseek-v4")) {
                    // NVIDIA's DeepSeek V4 model examples use this flag to disable thinking.
                    // Keep this separate from Nemotron's enable_thinking option.
                    requestBody.put("chat_template_kwargs", JSONObject().put("thinking", false))
                }
                payload = requestBody.toString()
            }
            val r: Http? = try {
                // NVIDIA's DeepSeek V4.1 Flash can have a long first-response delay.
                request(p, "POST", url, payload, key, readMs0 = if (p.id == "nvidia") 120000 else 30000)
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
                lastNote = "$model -> no response before timeout"
                // NVIDIA may accept a request but fail to return a first response in time.
                // Try one alternate catalog model, even in quota-saver mode, then report a
                // useful timeout diagnostic rather than silently abandoning the provider.
                if (p.id == "nvidia" && !refilled) {
                    refilled = true
                    val backups = candidates(p, vision).filter { it != model && it !in tried }
                    if (backups.isNotEmpty()) {
                        queue.addAll(0, backups)
                        onStatus?.invoke("NVIDIA response timed out; trying one alternate model...")
                        continue
                    }
                } else if (!Prefs.quotaSaver(ctx) && !refilled) {
                    refilled = true
                    queue.addAll(candidates(p, vision))
                    continue
                }
                throw AiException(
                    if (p.id == "nvidia")
                        "NVIDIA timed out waiting for $model. The request was sent, but no response arrived in time. Check NVIDIA model access/status and try again."
                    else "${p.label}: request timed out. Retry later or use the one configured backup."
                )
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
                // Retired catalog models must never be persisted as the provider's active choice.
                // Put distinct known fallbacks immediately after the failed model so quota-saver mode
                // still makes a real recovery attempt instead of failing on a single stale default.
                if (!refilled) {
                    refilled = true
                    val backups = candidates(p, vision).filter { it != model && it !in tried }
                    queue.addAll(0, backups)
                }
                continue
            }
            if (r.code in listOf(500, 502, 503, 504)) {
                // Temporary provider overload is not proof that the key or model is invalid.
                // Try one alternate model from the same provider before moving to a backup provider.
                lastNote = "$model -> HTTP ${r.code} ${r.body.replace("\n", " ").take(100)}"
                if (!refilled) {
                    refilled = true
                    val backups = candidates(p, vision).filter { it != model && it !in tried }
                    if (backups.isNotEmpty()) {
                        queue.addAll(0, backups)
                        onStatus?.invoke("${p.label} is temporarily busy (HTTP ${r.code}); trying an alternate model…")
                        continue
                    }
                }
                throw AiException("${p.label}: service temporarily busy (HTTP ${r.code}). Try again shortly or use another saved provider.")
            }
            if (r.code !in 200..299) throw AiException("${p.label}: error ${r.code} ${r.body.take(120)}")
            val content = extract(p, r.body)
            if (content.isBlank() || content == "null") {
                lastNote = "$model -> empty reply"
                // A selected catalog model can return HTTP 200 but no usable text.
                // Allow one bounded alternate-model attempt even in quota-saver mode;
                // never loop through the entire catalog or retry the same model.
                if (!refilled) {
                    refilled = true
                    val backups = candidates(p, vision).filter { it != model && it !in tried }
                    queue.addAll(0, backups)
                    if (backups.isNotEmpty()) {
                        onStatus?.invoke("${p.label} returned empty content; trying one alternate model...")
                        continue
                    }
                }
                throw AiException(p.label + ": model returned an empty response. Try another model or check that model's NVIDIA API access.")
            }
            Prefs.setModel(ctx, p, vision, model)
            return content
        }
        val kind = if (vision) "vision" else "chat"
        throw AiException("${p.label}: no $kind model worked. Last: $lastNote")
    }
}
