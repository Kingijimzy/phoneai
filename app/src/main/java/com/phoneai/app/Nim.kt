package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** A failure of one AI service. The next service can still be tried. */
class AiException(message: String, val httpStatus: Int? = null) : RuntimeException(message) {
    val rateLimited: Boolean get() = httpStatus == 429
}

/** Talks to the selected AI service, with bounded same-provider model recovery and cross-provider fallback. */
internal class Nim internal constructor(private val ctx: Context, private val commandMode: Boolean = false, sharedBudget: CommandRequestBudget? = null) :
    OrionModelProvider {
    // Structured tool calls are scoped to one synchronous chatTurn invocation.
    private val activeNativeTools = ThreadLocal<List<ToolDefinition>?>()
    private val nativeToolCalls = ThreadLocal<List<OrionModelToolCall>>()

    companion object {
        /** provider id -> time (ms) until which it is skipped (rate limited or bad key) */
        val cooldown = ConcurrentHashMap<String, Long>()

        /** provider id -> times of requests in the last minute (so we slow down BEFORE a limit is hit) */
        private val hits = ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<Long>>()
        /** Latest provider-reported quota headers, when that API exposes them. */
        private val providerQuota = ConcurrentHashMap<String, String>()
        /** Last confirmed provider/model per mode; response text is never stored here. */
        private val lastSuccess = ConcurrentHashMap<String, String>()
        /** Most recent failure per provider, kept only in memory for diagnostics. */
        private val lastFailure = ConcurrentHashMap<String, String>()
        /** Successful model catalogues, cached briefly and keyed by provider + saved-key fingerprint. */
        private val modelCatalogCache = ConcurrentHashMap<String, Pair<Long, List<String>>>()
        private const val MODEL_CATALOG_TTL_MS = 10 * 60 * 1000L

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

        /** Honest provider readiness summary; configured does not mean tested or guaranteed to work. */
        internal fun readinessLabel(configured: Boolean, paidOptInRequired: Boolean, allowPaid: Boolean, cooldownSeconds: Long): String = when {
            !configured -> "not connected"
            paidOptInRequired && !allowPaid -> "connected, but blocked until paid/metered providers are enabled"
            cooldownSeconds > 0 -> "configured, cooling down for $cooldownSeconds seconds"
            else -> "configured; eligible to try (actual model and service availability are checked per request)"
        }

        fun status(c: android.content.Context): String {
            val sb = StringBuilder("Provider readiness (saved/configured does not mean live-tested):")
            val now = System.currentTimeMillis()
            val allowPaid = Prefs.allowPaidProviders(c)
            for (p in Providers.all) {
                val configured = p.noKey || Prefs.hasKey(c, p.id)
                val cd = (((cooldown[p.id] ?: 0L) - now) / 1000).coerceAtLeast(0L)
                sb.append("\\n• ").append(p.label).append(": ")
                    .append(readinessLabel(configured, p.requiresPaidOptIn, allowPaid, cd))
                if (configured) {
                    sb.append("; local request counter ").append(usedLastMinute(p.id)).append("/").append(budget(p.id))
                        .append(" in the last minute (not the provider's actual quota)")
                    providerQuota[p.id]?.let { sb.append("; provider-reported quota: ").append(it) }
                }
                lastFailure[p.id]?.let { sb.append("; last error: ").append(it.take(140)) }
            }
            sb.append("\\nSelected chat provider: ").append(Prefs.provider(c, false).label)
            sb.append("\\nSelected screen-vision provider: ").append(Prefs.provider(c, true).label)
            lastSuccess["chat"]?.let { sb.append("\\nLast successful chat: ").append(it) }
            lastSuccess["vision"]?.let { sb.append("\\nLast successful screen-vision: ").append(it) }
            return sb.toString().trim()
        }
    }

    private fun nativeToolsRejected(failure: Throwable): Boolean {
        val messages = ArrayList<String>()
        var current: Throwable? = failure
        while (current != null) {
            messages += current.message.orEmpty()
            current = current.cause
        }
        val message = messages.joinToString(" ").lowercase()
        val explicitUnsupported = listOf(
            "does not support tools", "doesn't support tools",
            "does not support tool calling", "doesn't support tool calling",
            "tool calling is not supported", "tool calls are not supported",
            "function calling is not supported", "tools are not supported",
            "tool use is not supported", "tool_choice is not supported",
            "unsupported tool type", "unknown parameter: tools",
            "unrecognized request argument: tools", "tools is not supported"
        )
        return message.contains("400") && explicitUnsupported.any { message.contains(it) }
    }

    override fun chatTurn(
        messages: JSONArray,
        vision: Boolean,
        maxTokens: Int,
        temperature: Double,
        fastResponse: Boolean,
        tools: List<ToolDefinition>,
        onDelta: ((String) -> Unit)?,
        onToolCall: ((String, JSONObject) -> String)?
    ): OrionModelTurn {
        activeNativeTools.set(tools)
        nativeToolCalls.set(emptyList())
        return try {
            try {
                val text = chat(messages, vision, maxTokens, temperature, fastResponse, onDelta)
                OrionModelTurn(text, nativeToolCalls.get().orEmpty())
            } catch (failure: Exception) {
                if (tools.isEmpty() || cancelled || !nativeToolsRejected(failure)) throw failure
                val priorToolResults = (0 until messages.length()).any { index ->
                    messages.optJSONObject(index)?.optString("role") == "tool"
                }
                if (priorToolResults) {
                    throw AiException(
                        "The selected provider rejected a follow-up tool-call request after earlier tool results. Those completed results are preserved; no further actions were dispatched. Provider detail: ${failure.message.orEmpty().take(500)}",
                        400
                    )
                }
                if (!Prefs.autoSwitch(ctx)) {
                    throw AiException(
                        "The selected provider/model rejected Orion tool calling. Auto-switch is off, so Orion did not change models or dispatch a chat-only substitute. Choose a tool-capable model or enable Auto-switch.",
                        400
                    )
                }
                // When rotation is enabled, preserve normal chat availability after the
                // provider explicitly rejects native tools; the result is marked chat-only.
                activeNativeTools.set(emptyList())
                nativeToolCalls.set(emptyList())
                val fallbackMessages = JSONArray(messages.toString())
                fallbackMessages.optJSONObject(0)?.let { system ->
                    system.put("content", system.optString("content") +
                        "\nNative Orion tools are unavailable on this provider for this turn. You are chat-only: answer questions, but do not claim to perform phone, file, code, browser, research or external actions.")
                }
                OrionModelTurn(
                    chat(fallbackMessages, vision, maxTokens, temperature, fastResponse, onDelta),
                    toolCallingAvailable = false
                )
            }
        } finally {
            activeNativeTools.remove()
            nativeToolCalls.remove()
        }
    }

    /** Command mode may use its own saved text provider/model; vision and Talk stay unchanged. */
    private fun selectedProvider(vision: Boolean): Provider {
        if (commandMode && !vision) {
            Prefs.commandProviderId(ctx)?.let { Providers.find(it) }?.let { return it }
            return Prefs.defaultCommandProvider(ctx)
        }
        return Prefs.provider(ctx, vision)
    }

    /** Set by a caller that wants a lighter or heavier model for this one request (NVIDIA text only). */
    @Volatile internal var tier: ModelRouter.Tier? = null

    private fun selectedModel(p: Provider, vision: Boolean): String {
        tier?.let { t -> ModelRouter.modelFor(t, p.id, vision, Prefs.modelRouting(ctx) && Prefs.autoSwitch(ctx), Prefs.cachedModels(ctx, p.id).toSet())?.let { return it } }
        if (commandMode && !vision && Prefs.commandProviderId(ctx) == p.id) {
            Prefs.commandModelId(ctx)?.let { return it }
        }
        return Prefs.model(ctx, p, vision)
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

    /** A custom endpoint model catalogue is refreshed after an HTTP model failure, not speculatively. */
    private fun invalidateModelCatalogue(p: Provider) {
        val key = Prefs.key(ctx, p.id)
        if (key.isBlank()) return
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        modelCatalogCache.remove(p.id + ":" + fingerprint)
    }
    private val preferred = listOf(
        "deepseek-v4.1-flash", "deepseek-ai/deepseek-v4.1-flash",
        "llama-3.3-nemotron", "llama-3.3-70b", "llama-3.1-nemotron", "llama-3.1-70b",
        "llama-4", "qwen", "mistral-large", "mixtral", "llama-3.1-8b", "gemma", "mistral", "phi"
    )
    private val fallbacks: Map<String, Pair<List<String>, List<String>>> = mapOf(
        "gemini" to Pair(
            // Prefer supported current model families rather than retired 2.5 IDs that return 404.
            listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-flash-latest", "gemini-flash-lite-latest"),
            listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-flash-latest", "gemini-flash-lite-latest")
        ),
        "groq" to Pair(
            listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b"),
            listOf("qwen/qwen3.8-27b")
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

    internal class Http(val code: Int, val body: String, val retryAfter: Int = -1)

    /** Called with a short progress message, from the worker thread. */
    @Volatile
    var onStatus: ((String) -> Unit)? = null

    @Volatile
    private var cancelled = false

    @Volatile
    private var active: HttpURLConnection? = null
    private val connectionLock = Any()

    /** Atomically attach a new request unless Stop has already cancelled this generation. */
    private fun attachConnection(conn: HttpURLConnection): Boolean = synchronized(connectionLock) {
        if (cancelled) false else {
            active = conn
            true
        }
    }

    private fun detachConnection(conn: HttpURLConnection) {
        synchronized(connectionLock) {
            if (active === conn) active = null
        }
    }

    private val commandRequestBudget = sharedBudget ?: CommandRequestBudget(maxRequests = 1000)

    /** Starts a fresh request budget and identical-failure counter for one user command. */
    /** AI requests used by the current command (for the cost line). */
    override fun requestsUsed(): Int = commandRequestBudget.used()

    override fun beginCommand() {
        commandRequestBudget.reset()
    }

    override fun resume() {
        synchronized(connectionLock) { cancelled = false }
    }

    /** Aborts the request in flight and prevents a concurrently starting request from attaching. */
    override fun cancel() {
        val connection = synchronized(connectionLock) {
            cancelled = true
            active
        }
        try {
            connection?.disconnect()
        } catch (_: Exception) {
        }
    }

    private fun request(p: Provider, method: String, url: String, body: String?, key: String, readMs0: Int = 45000): Http {
        if (!commandRequestBudget.tryReserve()) {
            throw AiException("Stopped before sending another request: this command reached Orion's 1000-request safety ceiling. No additional HTTP request was made.")
        }
        // Count every network attempt here, including model catalogues and requests that fail to connect.
        noteHit(p.id)
        val readMs = if (p.id == "local") 240000 else readMs0
        val conn = URL(url).openConnection() as HttpURLConnection
        if (!attachConnection(conn)) {
            try { conn.disconnect() } catch (_: Exception) { }
            throw java.io.IOException("Stopped.")
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = 30000
            conn.readTimeout = if (p.noKey) 600000 else readMs
            if (p.anthropicStyle) {
                conn.setRequestProperty("x-api-key", key)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
            } else if (!(p.id == "gemini" && url.contains(":generateContent"))) {
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
            if (code !in 200..299) {
                // Store only a hash signature, never request or response text, for repeat-failure detection.
                val signature = listOf(p.id, method, url, body?.hashCode() ?: 0, code, text.hashCode()).joinToString("|")
                val repeats = commandRequestBudget.noteFailure(signature)
                if (repeats >= 3) {
                    throw AiException("${p.label}: stopped after three identical HTTP failures (HTTP $code). No more identical retries will be sent.")
                }
            }
            val ra = conn.getHeaderField("Retry-After")?.trim()?.toDoubleOrNull()?.toInt() ?: -1
            return Http(code, text, ra)
        } finally {
            try { conn.disconnect() } catch (_: Exception) { }
            detachConnection(conn)
        }
    }

    /**
     * Server-sent event transport for OpenAI-compatible chat completions.
     * Deltas are emitted as they arrive; HTTP errors before the first delta remain eligible for fallback.
     */
    internal fun requestStream(
        p: Provider, url: String, body: String, key: String, readMs0: Int, onDelta: (String) -> Unit
    ): Http {
        if (!commandRequestBudget.tryReserve()) {
            throw AiException("Stopped before sending another request: this command reached Orion's 1000-request safety ceiling. No additional HTTP request was made.")
        }
        noteHit(p.id)
        val conn = URL(url).openConnection() as HttpURLConnection
        if (!attachConnection(conn)) {
            try { conn.disconnect() } catch (_: Exception) { }
            throw java.io.IOException("Stopped.")
        }
        val answer = StringBuilder()
        val nonSseBody = StringBuilder()
        var terminalEventSeen = false
        var streamFinishReason = ""
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30000
            conn.readTimeout = if (p.noKey) 600000 else readMs0
            if (p.anthropicStyle) {
                conn.setRequestProperty("x-api-key", key)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
            } else conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val retry = conn.getHeaderField("Retry-After")?.trim()?.toDoubleOrNull()?.toInt() ?: -1
                return Http(code, error, retry)
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    if (cancelled) throw java.io.IOException("Stopped.")
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) {
                        if (line.isNotBlank() && !line.startsWith(":")) nonSseBody.append(line)
                        continue
                    }
                    val data = line.substringAfter("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") {
                        terminalEventSeen = true
                        break
                    }
                    val delta = try {
                        val root = JSONObject(data)
                        when {
                            p.anthropicStyle -> {
                                val type = root.optString("type")
                                if (type == "message_delta") {
                                    streamFinishReason = root.optJSONObject("delta")?.optString("stop_reason").orEmpty()
                                }
                                if (type == "message_stop") {
                                    terminalEventSeen = true
                                    break
                                }
                                if (type == "content_block_delta") root.optJSONObject("delta")?.optString("text", "") ?: "" else ""
                            }
                            p.id == "opencode-zen" -> {
                                val type = root.optString("type")
                                if (type == "response.completed" || type == "response.done") {
                                    val response = root.optJSONObject("response")
                                    streamFinishReason = response?.optJSONObject("incomplete_details")?.optString("reason")
                                        ?: response?.optString("status").orEmpty()
                                    terminalEventSeen = true
                                    break
                                }
                                if (type == "response.output_text.delta") root.optString("delta", "")
                                else if (type == "response.content_part.delta") root.optJSONObject("delta")?.optString("text", "") ?: ""
                                else ""
                            }
                            else -> {
                                val choices = root.optJSONArray("choices")
                                val choice = choices?.optJSONObject(0)
                                val finish = choice?.optString("finish_reason").orEmpty()
                                if (finish.isNotBlank() && finish != "null") streamFinishReason = finish
                                choice?.optJSONObject("delta")?.optString("content", "") ?: ""
                            }
                        }
                    } catch (_: Exception) { "" }
                    if (delta.isNotEmpty()) {
                        answer.append(delta)
                        onDelta(delta)
                    }
                }
            }
            if (!StreamCompletionPolicy.isComplete(answer.isNotEmpty(), terminalEventSeen, cancelled)) {
                throw java.io.IOException(
                    if (cancelled) "Stream cancelled by user."
                    else "Stream ended before its completion marker."
                )
            }
            if (answer.isEmpty() && nonSseBody.isNotBlank()) {
                // Some OpenAI-compatible gateways ignore stream=true and return a normal JSON body.
                // Preserve compatibility, though that provider cannot deliver incremental deltas.
                val complete = extract(p, nonSseBody.toString())
                if (complete.isNotBlank()) {
                    answer.append(complete)
                    onDelta(complete)
                }
            }
            val synthetic = when {
                p.anthropicStyle -> JSONObject()
                    .put("stop_reason", streamFinishReason)
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", answer.toString()))).toString()
                p.id == "opencode-zen" -> JSONObject().put("output_text", answer.toString())
                    .put("incomplete_details", if (streamFinishReason.equals("max_output_tokens", true) ||
                        streamFinishReason.equals("incomplete", true)) JSONObject().put("reason", "max_output_tokens") else JSONObject.NULL).toString()
                else -> JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", answer.toString()))
                    .put("finish_reason", if (streamFinishReason.isBlank()) JSONObject.NULL else streamFinishReason))).toString()
            }
            return Http(200, synthetic, -1)
        } finally {
            try { conn.disconnect() } catch (_: Exception) { }
            detachConnection(conn)
        }
    }

    private fun keyBad(r: Http): Boolean =
        r.code == 401 || r.code == 403 ||
            (r.code == 400 && (r.body.contains("API key", true) || r.body.contains("api_key", true)))

    /** Every model id this provider's key can see. Successful results are cached for ten minutes. */
    override fun allModels(p: Provider): List<String> {
        val key = Prefs.key(ctx, p.id)
        if (key.isEmpty()) throw IllegalStateException("No ${p.label} key saved.")
        if (!Prefs.allowPaidProviders(ctx) && p.requiresPaidOptIn) {
            throw IllegalStateException("Model discovery for ${p.label} is blocked until you explicitly allow paid/metered providers in Settings.")
        }
        val keyFingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val cacheKey = p.id + ":" + keyFingerprint
        val cached = modelCatalogCache[cacheKey]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.first < MODEL_CATALOG_TTL_MS) {
            return cached.second.filter { id ->
                ProviderBillingPolicy.isAllowed(p.id, id, p.requiresPaidOptIn, Prefs.allowPaidProviders(ctx))
            }
        }
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
            JSONObject(trimmed).optJSONArray("data") ?: JSONArray()
        }
        val ids = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            ids.add(o.optString("id").removePrefix("models/"))
        }
        val result = ids.filter { it.isNotBlank() }.distinct().sorted()
        modelCatalogCache[cacheKey] = Pair(System.currentTimeMillis(), result)
        Prefs.saveCachedModels(ctx, p.id, result)
        return result.filter { id ->
            ProviderBillingPolicy.isAllowed(p.id, id, p.requiresPaidOptIn, Prefs.allowPaidProviders(ctx))
        }
    }

    /**
     * One bounded, no-fallback live probe used before saving a selected NVIDIA model.
     * A model appearing in /models is not proof that its chat endpoint is enabled for this key.
     * Returns an empty string only when a real chat completion returns usable text.
     */
    override fun testModel(p: Provider, model: String, vision: Boolean): String {
        // A model-picker probe is its own user command and must not inherit the
        // previous probe's request count when the same Nim instance is reused.
        beginCommand()
        if (!ProviderBillingPolicy.isAllowed(p.id, model, p.requiresPaidOptIn, Prefs.allowPaidProviders(ctx))) {
            return "Blocked: ${p.label} / $model may incur paid or metered usage. Enable the explicit paid-provider setting in Settings only if you accept possible charges."
        }
        if (Prefs.key(ctx, p.id).isEmpty()) return "No ${p.label} API key is saved."
        val probePrefs = ctx.getSharedPreferences("phoneai", Context.MODE_PRIVATE)
        val probeKey = "nvidia_probe_${if (vision) "vision" else "chat"}_${model.hashCode()}"
        val probeTime = probePrefs.getLong("${probeKey}_at", 0L)
        val cachedResult = probePrefs.getString("${probeKey}_result", null)
        val cacheAge = if (cachedResult == "OK") 30 * 60 * 1000L else 2 * 60 * 1000L
        if (cachedResult != null && System.currentTimeMillis() - probeTime < cacheAge) {
            return if (cachedResult == "OK") "" else cachedResult
        }
        fun rememberFailure(message: String): String {
            probePrefs.edit().putString("${probeKey}_result", message)
                .putLong("${probeKey}_at", System.currentTimeMillis()).apply()
            return message
        }
        // Test chat availability with text for both pickers. A 1x1 synthetic image is
        // rejected by some vision endpoints and falsely hides otherwise usable models.
        val userContent = "Reply OK."
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content",
                "You are checking whether this chat endpoint works. Reply with only OK."))
            .put(JSONObject().put("role", "user").put("content", userContent))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0)
            .put("max_tokens", 1024)
        if (p.id == "nvidia" && model.lowercase().contains("deepseek-v4")) {
            body.put("chat_template_kwargs", JSONObject().put("thinking", false))
        } else if (p.id == "nvidia" && model.lowercase().contains("nemotron-3.5-super-vl")) {
            body.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        } else if (p.id == "nvidia" && model.lowercase().contains("nemotron-3.5-lightning")) {
            body.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        }
        val response = try {
            if (p.id == "gemini") {
                request(p, "POST", geminiUrl(p, model, Prefs.key(ctx, p.id)),
                    geminiBody(messages, 32, 0.0, emptyList()), Prefs.key(ctx, p.id), readMs0 = 60000)
            } else if (p.id == "opencode-zen") {
                val input = JSONArray()
                for (i in 0 until messages.length()) {
                    val message = messages.optJSONObject(i) ?: continue
                    input.put(JSONObject().put("role", message.optString("role", "user"))
                        .put("content", message.opt("content") ?: ""))
                }
                val responsesBody = JSONObject().put("model", model).put("input", input)
                    .put("max_output_tokens", 32).put("temperature", 0)
                request(p, "POST", p.base + "/responses", responsesBody.toString(),
                    Prefs.key(ctx, p.id), readMs0 = 60000)
            } else {
                request(p, "POST", p.base + "/chat/completions", body.toString(),
                    Prefs.key(ctx, p.id), readMs0 = 60000)
            }
        } catch (e: Exception) {
            return rememberFailure("Connection test failed (${e.javaClass.simpleName}). Check internet and model access.")
        }
        if (response.code !in 200..299) {
            val detail = response.body.replace("\\s+".toRegex(), " ").take(120)
            return rememberFailure("Model test failed: HTTP ${response.code}" + if (detail.isBlank()) "." else " — $detail")
        }
        val answer = extract(p, response.body)
        if (answer.isBlank() || answer == "null") {
            return rememberFailure("The endpoint returned HTTP ${response.code}, but no readable chat text. Choose another model.")
        }
        // Remember successful probes briefly so reopening the picker does not spend quota again.
        probePrefs.edit()
            .putBoolean("${probeKey}_ok", true)
            .putString("${probeKey}_result", "OK")
            .putLong("${probeKey}_at", System.currentTimeMillis())
            .apply()
        return ""
    }

    fun isVision(id: String): Boolean = visionKeys.any { id.lowercase().contains(it) }

    private fun toolDescriptionLimit(recoveryPass: Int): Int = when (recoveryPass) { 0 -> 1000; 1 -> 300; else -> 120 }\n\n    private fun candidates(p: Provider, vision: Boolean): List<String> {
        // Normally quota-saver mode avoids the extra /models request. NVIDIA's catalog
        // retires model IDs frequently (410 Gone), so discover IDs currently exposed to this
        // key after a model failure even in quota-saver mode rather than retrying retired IDs.
        val shouldDiscover = ProviderModelCapabilityPolicy.shouldDiscoverAfterFailure(p.id, Prefs.quotaSaver(ctx))
        val ids = if (!shouldDiscover) emptyList() else try {
            if (p.id.startsWith("custom_")) invalidateModelCatalogue(p)
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
            "groq", "cerebras", "mistral", "sambanova", "together", "fireworks", "huggingface" -> {
                // Prefer models actually advertised by this key's live catalogue. Static IDs
                // are only bounded recovery candidates when discovery is unavailable (e.g. quota saver).
                val compatible = if (vision) ok.filter { isVision(it) } else ok.filter { !isVision(it) }
                for (pref in preferred) {
                    compatible.filter { it.contains(pref, ignoreCase = true) }.forEach { if (it !in out) out.add(it) }
                }
                compatible.forEach { if (it !in out) out.add(it) }
                out.addAll(if (vision) fb.second else fb.first)
            }
            "anthropic", "openai", "deepseek" -> {
                out.addAll(if (vision) fb.second else fb.first)
            }
            else -> {
                val compatible = if (vision) ok.filter { isVision(it) } else ProviderModelCapabilityPolicy.textCandidates(ok)
                if (vision) {
                    out.addAll(compatible)
                } else {
                    val txt = compatible
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
        return out.distinct().filter { model ->
            ProviderBillingPolicy.isAllowed(p.id, model, p.requiresPaidOptIn, Prefs.allowPaidProviders(ctx))
        }
    }

    /** Converts OpenAI-style messages to Anthropic's format. */
    private fun anthropicBody(model: String, messages: JSONArray, tokens: Int, temperature: Double, tools: List<ToolDefinition> = emptyList(), descriptionLimit: Int = 1000): String {
        val system = StringBuilder()
        val out = JSONArray()
        fun appendMessage(role: String, content: Any) {
            val last = if (out.length() > 0) out.optJSONObject(out.length() - 1) else null
            if (last != null && last.optString("role") == role) {
                val previous = last.opt("content")
                if (previous is JSONArray && content is JSONArray) {
                    for (i in 0 until content.length()) previous.put(content.get(i))
                    return
                }
                if (previous is String && content is String) {
                    last.put("content", previous + "\n" + content)
                    return
                }
            }
            out.put(JSONObject().put("role", role).put("content", content))
        }
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role")
            val c = m.opt("content")
            if (role == "system") {
                if (system.isNotEmpty()) system.append("\n")
                system.append(c?.toString() ?: "")
                continue
            }
            if (role == "assistant" && m.optJSONArray("tool_calls") != null) {
                val blocks = JSONArray()
                if (!c?.toString().isNullOrBlank()) blocks.put(JSONObject().put("type", "text").put("text", c.toString()))
                val calls = m.optJSONArray("tool_calls") ?: JSONArray()
                for (j in 0 until calls.length()) {
                    val call = calls.optJSONObject(j) ?: continue
                    val fn = call.optJSONObject("function") ?: continue
                    val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }.getOrDefault(JSONObject())
                    blocks.put(JSONObject().put("type", "tool_use")
                        .put("id", call.optString("id")).put("name", fn.optString("name")).put("input", args))
                }
                appendMessage("assistant", blocks)
                continue
            }
            if (role == "tool") {
                val result = JSONArray().put(JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", m.optString("tool_call_id"))
                    .put("content", c?.toString() ?: ""))
                appendMessage("user", result)
                continue
            }
            val content: Any = if (c is JSONArray) {
                val parts = JSONArray()
                for (j in 0 until c.length()) {
                    val part = c.optJSONObject(j) ?: continue
                    if (part.optString("type") == "image_url") {
                        val url = part.optJSONObject("image_url")?.optString("url").orEmpty()
                        val data = url.substringAfter("base64,", "")
                        parts.put(JSONObject().put("type", "image").put(
                            "source", JSONObject().put("type", "base64")
                                .put("media_type", "image/jpeg").put("data", data)
                        ))
                    } else {
                        parts.put(JSONObject().put("type", "text").put("text", part.optString("text")))
                    }
                }
                parts
            } else c?.toString() ?: ""
            appendMessage(if (role == "assistant") "assistant" else "user", content)
        }
        val o = JSONObject().put("model", model).put("max_tokens", tokens).put("messages", out)
        if (system.isNotEmpty()) o.put("system", system.toString())
        o.put("temperature", temperature)
        if (tools.isNotEmpty()) o.put("tools", OrionToolContractCodec.anthropic(tools, descriptionLimit))
        return o.toString()
    }

    /** Gemini's native generateContent request/response format; no OpenAI compatibility shim. */
    private fun geminiBody(
        messages: JSONArray,
        tokens: Int,
        temperature: Double,
        tools: List<ToolDefinition>,
        descriptionLimit: Int = 1000
    ): String {
        val system = StringBuilder()
        val contents = JSONArray()
        val callNames = HashMap<String, String>()
        fun appendContent(role: String, parts: JSONArray) {
            if (parts.length() == 0) return
            val previous = if (contents.length() > 0) contents.optJSONObject(contents.length() - 1) else null
            if (previous != null && previous.optString("role") == role) {
                val existing = previous.optJSONArray("parts") ?: JSONArray()
                for (index in 0 until parts.length()) existing.put(parts.get(index))
                previous.put("parts", existing)
            } else {
                contents.put(JSONObject().put("role", role).put("parts", parts))
            }
        }
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val role = message.optString("role", "user")
            val content = message.opt("content")
            if (role == "system") {
                if (system.isNotEmpty()) system.append("\n")
                system.append(content?.toString().orEmpty())
                continue
            }
            if (role == "assistant" && message.optJSONArray("tool_calls") != null) {
                val parts = JSONArray()
                if (!content?.toString().isNullOrBlank()) parts.put(JSONObject().put("text", content.toString()))
                val calls = message.optJSONArray("tool_calls") ?: JSONArray()
                for (j in 0 until calls.length()) {
                    val call = calls.optJSONObject(j) ?: continue
                    val fn = call.optJSONObject("function") ?: continue
                    val name = fn.optString("name")
                    val id = call.optString("id")
                    callNames[id] = name
                    val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }.getOrDefault(JSONObject())
                    val functionCall = JSONObject().put("name", name).put("args", args).put("id", id)
                    call.optString("thought_signature").ifBlank { call.optString("thoughtSignature") }.takeIf { it.isNotBlank() }?.let { functionCall.put("thoughtSignature", it) }
                    parts.put(JSONObject().put("functionCall", functionCall))
                }
                appendContent("model", parts)
                continue
            }
            if (role == "tool") {
                val id = message.optString("tool_call_id")
                val name = message.optString("name").ifBlank { callNames[id].orEmpty() }
                val response = JSONObject().put("name", name)
                    .put("response", JSONObject().put("content", content?.toString().orEmpty()))
                if (id.isNotBlank()) response.put("id", id)
                val parts = JSONArray().put(JSONObject().put("functionResponse", response))
                appendContent("user", parts)
                continue
            }
            val parts = JSONArray()
            if (content is JSONArray) {
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    if (part.optString("type") == "image_url") {
                        val imageUrl = part.optJSONObject("image_url")?.optString("url").orEmpty()
                        val mime = Regex("^data:([^;]+);base64,").find(imageUrl)?.groupValues?.get(1) ?: "image/jpeg"
                        val data = imageUrl.substringAfter("base64,", "")
                        if (data.isNotBlank()) parts.put(JSONObject().put("inlineData",
                            JSONObject().put("mimeType", mime).put("data", data)))
                    } else if (part.has("text")) {
                        parts.put(JSONObject().put("text", part.optString("text")))
                    }
                }
            } else if (content != null) {
                parts.put(JSONObject().put("text", content.toString()))
            }
            appendContent(if (role == "assistant") "model" else "user", parts)
        }
        val body = JSONObject().put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", tokens).put("temperature", temperature))
        if (system.isNotEmpty()) body.put("systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system.toString()))))
        if (tools.isNotEmpty()) {
            body.put("tools", OrionToolContractCodec.gemini(tools, descriptionLimit))
            body.put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", "AUTO")))
        }
        return body.toString()
    }

    private fun geminiUrl(p: Provider, model: String, key: String): String {
        val root = p.base.substringBefore("/openai").trimEnd('/')
        val encodedModel = java.net.URLEncoder.encode(model, "UTF-8")
        val encodedKey = java.net.URLEncoder.encode(key, "UTF-8")
        return "$root/models/$encodedModel:generateContent?key=$encodedKey"
    }

    private fun extract(p: Provider, body: String): String = try {
        if (p.id == "gemini") {
            val parts = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            buildString {
                for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text", "").orEmpty())
            }
        } else if (p.id == "opencode-zen") {
            val root = JSONObject(body)
            val direct = root.optString("output_text", "")
            if (direct.isNotBlank()) direct else {
                val output = root.optJSONArray("output") ?: JSONArray()
                val text = StringBuilder()
                for (i in 0 until output.length()) {
                    val item = output.optJSONObject(i) ?: continue
                    val content = item.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "output_text" || part.has("text")) {
                            text.append(part.optString("text", ""))
                        }
                    }
                }
                text.toString()
            }
        } else if (p.anthropicStyle) {
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

    private fun responseWasTruncated(p: Provider, body: String): Boolean =
        OrionResponseCompleteness.wasTruncated(p.id, body, p.anthropicStyle)

    /** A command begins at its explicit caller; the shared budget covers every fallback. */
    override fun chat(messages: JSONArray, vision: Boolean, maxTokens: Int, temperature: Double, fastResponse: Boolean, onDelta: ((String) -> Unit)?): String {
        if (cancelled) throw RuntimeException("Stopped.")
        // Do not reset here: Agent.run() can call chat() several times for one
        // multi-step user command. Its explicit beginCommand() must cover the
        // entire run, including every model retry and any permitted 429 backup.
        val selected = selectedProvider(vision)
        fun configured(p: Provider): Boolean =
            (Prefs.hasKey(ctx, p.id) || p.noKey) && (!vision || p.hasVision)
        fun modelAllowed(p: Provider): Boolean = ProviderBillingPolicy.isAllowed(
            p.id, selectedModel(p, vision), p.requiresPaidOptIn, Prefs.allowPaidProviders(ctx)
        )
        if (configured(selected) && !modelAllowed(selected)) {
            throw IllegalStateException(
                "${selected.label} / ${selectedModel(selected, vision)} may incur paid or metered usage. " +
                    "Orion blocked the request because paid-provider use is off. Enable the explicit paid-provider setting in Settings only if you accept possible charges."
            )
        }
        fun usable(p: Provider): Boolean = configured(p) && modelAllowed(p)

        var primary: Provider = if (configured(selected)) selected
        else if (!Prefs.autoSwitch(ctx)) {
            throw IllegalStateException("${selected.label} is selected but not configured for ${if (vision) "vision" else "chat"}. Auto-switch is off, so Orion will not silently use another provider.")
        } else {
            Providers.all.firstOrNull { usable(it) }
                ?: if (Providers.all.any { configured(it) }) {
                    throw IllegalStateException("Only paid or metered providers are configured. Orion blocked the request because paid-provider use is off; enable the explicit setting in Settings only if you accept possible charges.")
                } else {
                    throw IllegalStateException("No compatible AI provider is configured. Add a key for a provider that supports ${if (vision) "vision" else "chat"} in Settings.")
                }
        }

        // --- Offline detection (Requirement 11) ---
        // When offline mode is on, or the device has no reachable network, skip cloud
        // providers and prefer the local model. Never burn request budget on timeouts.
        val offline = Prefs.offlineMode(ctx) || !OrionNetworkMonitor.isOnline(ctx)
        if (offline && !OrionNetworkMonitor.isOfflineCapable(primary.id)) {
            val local = Providers.find(OrionNetworkMonitor.offlinePreferredProviderId())
            if (local != null && usable(local) && (Prefs.offlineMode(ctx) || Prefs.autoSwitch(ctx))) {
                onStatus?.invoke("Offline: switching to local model (${local.label})...")
                // Force the local provider as primary; backups are irrelevant offline.
                primary = local
            } else if (Prefs.failFastOffline(ctx)) {
                throw RuntimeException("Offline: no local model provider is configured. Add an Ollama key in Settings or disable offline mode.")
            }
        }

        val now = System.currentTimeMillis()
        // Auto-switch may try eligible saved providers in order, but the shared 1000-request
        // command budget is the hard cap across model discovery, retries, and provider fallback.
        val backups = if (Prefs.autoSwitch(ctx)) {
            Providers.all
                .filter { it.id != primary.id && usable(it) && (cooldown[it.id] ?: 0L) <= now }
                .take(ProviderFallbackPolicy.MAX_BACKUP_PROVIDERS)
        } else emptyList()
        val mode = if (vision) "vision" else "chat"

        val failures = ArrayList<String>()
        val emitted = java.util.concurrent.atomic.AtomicBoolean(false)
        val delta: ((String) -> Unit)? = onDelta?.let { callback -> { part -> if (part.isNotEmpty()) { emitted.set(true); callback(part) } } }

        fun attempt(p: Provider, hasBackup: Boolean): String {
            if (cancelled) throw RuntimeException("Stopped.")
            onStatus?.invoke("Using provider: ${p.label}...")
            val answer = chatOne(p, messages, vision, maxTokens, temperature, hasBackup, fastResponse, delta)
            lastSuccess[mode] = p.label + " / " + selectedModel(p, vision)
            lastFailure.remove(p.id)
            return answer
        }
        val order = listOf(primary) + backups
        for ((i, p) in order.withIndex()) {
            try {
                return attempt(p, i < order.size - 1)
            } catch (e: Exception) {
                if (cancelled) throw RuntimeException("Stopped.")
                if (!StreamFallbackPolicy.mayTryAnotherProvider(emitted.get(), cancelled)) {
                    if (emitted.get()) throw RuntimeException("The response was interrupted after partial text. Orion stopped provider fallback to prevent duplicated output. Tap Retry to start a fresh response.", e)
                    throw RuntimeException("Stopped.", e)
                }
                val msg = (e.message ?: "Unknown provider error").take(220)
                lastFailure[p.id] = msg
                failures.add(msg)
                if (!ProviderFallbackPolicy.shouldTryBackup(e, Prefs.autoSwitch(ctx)) || i == order.size - 1) {
                    val tried = if (order.size > 1) " Tried ${i + 1} provider(s): ${order.take(i + 1).joinToString { it.label }}." else
                        " No other compatible saved provider is available to rotate to; add a second key in Settings."
                    throw RuntimeException(failures.joinToString(" | ") + tried, e)
                }
                val next = order[i + 1]
                onStatus?.invoke("${p.label} failed (${msg.take(60)}); switching to ${next.label}...")
            }
        }
        throw RuntimeException(failures.joinToString(" | "))
    }

    private fun chatOne(
        p: Provider, messages: JSONArray, vision: Boolean, maxTokens: Int, temperature: Double,
        hasBackup: Boolean, fastResponse: Boolean = false, onDelta: ((String) -> Unit)? = null,
        continuationDepth: Int = 0, payloadRecoveryDepth: Int = 0
    ): String {
        val key = Prefs.key(ctx, p.id)
        // Gemini needs room for its internal thinking. NVIDIA Nemotron 3.5 Super VL
        // reasons by default; short probes (e.g. max_tokens=16) can spend the entire
        // budget reasoning and return choices[0].message.content="" (finish_reason=length).
        // Disable thinking for reliable direct chat/vision replies and provide a safe minimum.
        // Keep normal turns responsive: the previous 32k minimum gave every short
        // chat/tool-planning request a huge output budget. Truncated long answers can
        // continue through the existing bounded continuation path.
        val outputCeiling = if (fastResponse) 2048 else 8192
        val requestedOutput = maxTokens.coerceIn(512, outputCeiling)
        val tokens = when (p.id) {
            // Provider-specific thinking overhead is included, then clamped to the selected model's real output ceiling.
            "gemini" -> if (fastResponse) maxOf(requestedOutput * 2, 2048) else maxOf(requestedOutput * 2, 8192)
            "nvidia" -> requestedOutput
            else -> requestedOutput
        }
        val initialModel = selectedModel(p, vision)
        val queue = mutableListOf(initialModel)
        val tried = HashSet<String>()
        val rateLimitRetried = HashSet<String>()
        var refilled = false
        var lastNote = ""
        var lastHttpStatus: Int? = null
        var attempts = 0
        // Attempts are bounded. Same-provider alternate models are queued only when
        // Auto-switch is enabled; with it off this queue contains only the selected model.
        val maxAttempts = if (fastResponse) 3 else 5
        val start = System.currentTimeMillis()
        val totalTimeoutMs = when {
            fastResponse -> 30000L
            p.noKey -> 600000L
            else -> 180000L
        }
        while (queue.isNotEmpty() && attempts < maxAttempts && System.currentTimeMillis() - start < totalTimeoutMs) {
            if (cancelled) throw RuntimeException("Stopped.")
            val model = queue.removeAt(0)
            if (!tried.add(model)) continue
            val modelCapabilities = OrionProviderContextPolicy.capabilities(p.id, model)
            if (vision && !modelCapabilities.vision) {
                lastNote = "Model $model is not verified for image input; skipping this model."
                continue
            }
            // Recompute budgets for every fallback model; context limits can differ within a provider.
            val boundedTokens = tokens.coerceAtMost(modelCapabilities.maxOutputTokens)
            val requestMessages = OrionProviderContextPolicy.fitMessages(messages, modelCapabilities, boundedTokens)
            val originalImageCount = OrionProviderContextPolicy.newestUserImageCount(messages)
            val retainedImageCount = OrionProviderContextPolicy.newestUserImageCount(requestMessages)
            if (vision && retainedImageCount < originalImageCount) {
                throw AiException("The selected model context cannot safely fit all attached images with the current conversation. Start a fresh chat or attach fewer images.", 413)
            }
            attempts++
            onStatus?.invoke(p.label + ": preparing " + model.substringAfterLast('/') + "...")
            if (cancelled) throw RuntimeException("Stopped.")
            onStatus?.invoke(p.label + ": waiting for " + model.substringAfterLast('/') + " response...")
            val url: String
            val payload: String
            if (p.id == "gemini") {
                url = geminiUrl(p, model, key)
                payload = geminiBody(requestMessages, boundedTokens, temperature, activeNativeTools.get().orEmpty(), toolDescriptionLimit(payloadRecoveryDepth))
            } else if (p.anthropicStyle) {
                url = p.base + "/messages"
                payload = anthropicBody(model, requestMessages, boundedTokens, temperature, activeNativeTools.get().orEmpty(), toolDescriptionLimit(payloadRecoveryDepth))
            } else if (p.id == "opencode-zen") {
                url = p.base + "/responses"
                val input = JSONArray()
                for (i in 0 until requestMessages.length()) {
                    val message = requestMessages.optJSONObject(i) ?: continue
                    val role = message.optString("role", "user")
                    if (role == "tool") {
                        input.put(JSONObject().put("type", "function_call_output")
                            .put("call_id", message.optString("tool_call_id"))
                            .put("output", message.opt("content")?.toString() ?: ""))
                    } else if (role == "assistant" && message.optJSONArray("tool_calls") != null) {
                        val calls = message.optJSONArray("tool_calls") ?: JSONArray()
                        val assistantText = message.opt("content")?.toString().orEmpty()
                        if (assistantText.isNotBlank()) input.put(JSONObject()
                            .put("role", "assistant").put("content", assistantText))
                        for (j in 0 until calls.length()) {
                            val call = calls.optJSONObject(j) ?: continue
                            val fn = call.optJSONObject("function") ?: continue
                            input.put(JSONObject().put("type", "function_call")
                                .put("call_id", call.optString("id"))
                                .put("name", fn.optString("name"))
                                .put("arguments", fn.optString("arguments", "{}")))
                        }
                    } else {
                        input.put(JSONObject().put("role", if (role == "assistant") "assistant" else "user")
                            .put("content", message.opt("content") ?: ""))
                    }
                }
                val responseBody = JSONObject().put("model", model).put("input", input)
                    .put("max_output_tokens", boundedTokens).put("temperature", temperature)
                val toolSpecs = activeNativeTools.get().orEmpty()
                if (toolSpecs.isNotEmpty()) responseBody.put("tools", OrionToolContractCodec.responses(toolSpecs, toolDescriptionLimit(payloadRecoveryDepth)))
                payload = responseBody.toString()
            } else {
                url = p.base + "/chat/completions"
                val requestBody = JSONObject()
                    .put("model", model)
                    .put("messages", requestMessages)
                    .put("temperature", temperature)
                    .put("max_tokens", boundedTokens)
                val toolSpecs = activeNativeTools.get().orEmpty()
                if (toolSpecs.isNotEmpty()) {
                    requestBody.put("tools", OrionToolContractCodec.openAi(toolSpecs, toolDescriptionLimit(payloadRecoveryDepth)))
                    requestBody.put("tool_choice", "auto")
                }
                if (onDelta != null && !vision && toolSpecs.isEmpty()) requestBody.put("stream", true)
                if (p.id == "nvidia" && model.equals("nvidia/nemotron-3.5-super-vl-120b-a12b", ignoreCase = true)) {
                    requestBody.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                } else if (p.id == "nvidia" && model.lowercase().contains("deepseek-v4")) {
                    requestBody.put("chat_template_kwargs", JSONObject().put("thinking", false))
                }
                payload = requestBody.toString()
            }
            val r: Http? = try {
                // NVIDIA's DeepSeek V4.1 Flash can have a long first-response delay.
                if (onDelta != null && !vision && !p.anthropicStyle && p.id != "opencode-zen" && p.id != "gemini" && activeNativeTools.get().isNullOrEmpty()) {
                    requestStream(p, url, payload, key, if (fastResponse) 30000 else 180000, onDelta)
                } else request(p, "POST", url, payload, key, readMs0 = if (fastResponse) 30000 else 180000)
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
                lastHttpStatus = 504
                // NVIDIA may accept a request but fail to return a first response in time.
                // Try one alternate catalog model, even in quota-saver mode, then report a
                // useful timeout diagnostic rather than silently abandoning the provider.
                if (Prefs.autoSwitch(ctx) && p.id == "nvidia" && !refilled) {
                    refilled = true
                    val backups = candidates(p, vision).filter { it != model && it !in tried }
                    if (backups.isNotEmpty()) {
                        queue.addAll(0, backups)
                        onStatus?.invoke("NVIDIA response timed out; trying one alternate model...")
                        continue
                    }
                } else if (Prefs.autoSwitch(ctx) && !Prefs.quotaSaver(ctx) && !refilled) {
                    refilled = true
                    queue.addAll(candidates(p, vision))
                    continue
                }
                throw AiException(
                    if (p.id == "nvidia")
                        "NVIDIA timed out waiting for $model. The request was sent, but no response arrived in time. Check NVIDIA model access/status and try again."
                    else "${p.label}: request timed out. Retry later or use the one configured backup.",
                    504
                )
            }
            if (keyBad(r)) throw AiException("${p.label}: key rejected (${r.code}). Save a new key.")
            if (r.code == 413) {
                lastHttpStatus = 413
                if (payloadRecoveryDepth < 3) {
                    val compacted = OrionProviderPayloadRecovery.compact(requestMessages, payloadRecoveryDepth + 1)
                    val beforeSize = requestMessages.toString().length
                    if (compacted.toString().length < (beforeSize * 0.94).toInt()) {
                        onStatus?.invoke("${p.label}: request was too large; trimming older conversation context and retrying the same model…")
                        return chatOne(
                            p, compacted, false, maxTokens, temperature, hasBackup,
                            fastResponse, onDelta, continuationDepth, payloadRecoveryDepth + 1
                        )
                    }
                }
                cooldown[p.id] = System.currentTimeMillis() + 5000L
                throw AiException(
                    "${p.label}: request payload is still too large (HTTP 413) after bounded context recovery. Orion preserved your latest request and will try another eligible provider if Auto-switch is enabled.",
                    413
                )
            }
            if (r.code == 429) {
                val wait = if (r.retryAfter in 1..60) r.retryAfter else 60
                // OpenRouter may rate-limit one model while another model on the same
                // account remains available. Try another unattempted model before cooling
                // down the whole provider, including models already queued after a 404/410.
                if (Prefs.autoSwitch(ctx) && p.id == "openrouter" && attempts < maxAttempts) {
                    val alternatives = (queue + candidates(p, vision))
                        .filter { it != model && it !in tried }
                        .distinct()
                    if (alternatives.isNotEmpty()) {
                        queue.clear()
                        queue.addAll(alternatives)
                        onStatus?.invoke("OpenRouter rate-limited $model (429); trying another OpenRouter model...")
                        continue
                    }
                }
                // Do not declare the request exhausted immediately when the provider gives
                // a short Retry-After. Wait briefly and retry this model once, while remaining
                // cancellable. Longer waits are better handled by a healthy backup provider.
                if (wait in 1..5 && rateLimitRetried.add(model) && attempts < maxAttempts) {
                    onStatus?.invoke("${p.label} rate-limited ${model.substringAfterLast('/')}; waiting ${wait}s before one retry...")
                    var remainingMs = wait * 1000L
                    while (remainingMs > 0L) {
                        if (cancelled || Thread.currentThread().isInterrupted) throw RuntimeException("Stopped.")
                        val slice = minOf(remainingMs, 200L)
                        try { Thread.sleep(slice) } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw RuntimeException("Stopped.")
                        }
                        remainingMs -= slice
                    }
                    tried.remove(model)
                    queue.add(0, model)
                    continue
                }
                cooldown[p.id] = System.currentTimeMillis() + wait.coerceIn(8, 60) * 1000L
                throw AiException("${p.label}: rate limit reached (429); waited/retried when the provider supplied a short delay. ${if (hasBackup) "Trying another eligible provider." else "Retry after about ${wait.coerceIn(8, 60)} seconds or add another provider."}", 429)
            }
            if (r.code == 402) throw AiException("${p.label}: out of credit (402).")
            val modelProblem = r.code == 404 || r.code == 410 || r.code == 422 ||
                (r.code == 400 && r.body.isNotEmpty())
            if (modelProblem) {
                lastHttpStatus = r.code
                lastNote = "$model -> ${r.code} ${r.body.replace("\n", " ").take(100)}"
                // Retired catalog models must never be persisted as the provider's active choice.
                // Put distinct known fallbacks immediately after the failed model so quota-saver mode
                // still makes a real recovery attempt instead of failing on a single stale default.
                if (Prefs.autoSwitch(ctx) && !refilled) {
                    refilled = true
                    val backups = candidates(p, vision).filter { it != model && it !in tried }
                    queue.addAll(0, backups)
                }
                if (queue.isEmpty()) throw AiException(
                    if (Prefs.autoSwitch(ctx)) "${p.label}: selected model $model returned HTTP ${r.code}; no eligible alternate model was available."
                    else "${p.label}: selected model $model returned HTTP ${r.code}; Auto-switch is off, so Orion kept the selected model.",
                    r.code
                )
                continue
            }
            if (r.code in listOf(500, 502, 503, 504)) {
                lastHttpStatus = r.code
                // Temporary provider overload is not proof that the key or model is invalid.
                // Try one alternate model from the same provider before moving to a backup provider.
                lastNote = "$model -> HTTP ${r.code} ${r.body.replace("\n", " ").take(100)}"
                if (Prefs.autoSwitch(ctx) && !refilled) {
                    refilled = true
                    val backups = candidates(p, vision).filter { it != model && it !in tried }
                    if (backups.isNotEmpty()) {
                        queue.addAll(0, backups)
                        onStatus?.invoke("${p.label} is temporarily busy (HTTP ${r.code}); trying an alternate model…")
                        continue
                    }
                }
                throw AiException(
                    if (Prefs.autoSwitch(ctx)) "${p.label}: service temporarily busy (HTTP ${r.code}). Try again shortly or use another saved provider."
                    else "${p.label}: selected model $model is temporarily busy (HTTP ${r.code}); Auto-switch is off, so Orion did not change models."
                )
            }
            if (r.code !in 200..299) throw AiException("${p.label}: error ${r.code} ${r.body.take(120)}")
            val requestedTools = activeNativeTools.get().orEmpty()
            val truncated = responseWasTruncated(p, r.body)
            if (truncated && requestedTools.isNotEmpty()) {
                throw AiException("${p.label}: response hit its output-token limit while preparing a tool call. No partial tool call was dispatched; reduce the task scope or increase the model output limit.")
            }
            val parsedTurn = if (requestedTools.isNotEmpty()) {
                OrionNativeToolCallCodec.parse(p.id, r.body) { name -> ToolRegistry.find(name) != null }
            } else OrionNativeToolCallCodec.Turn(extract(p, r.body), emptyList())
            if (parsedTurn.calls.isNotEmpty()) {
                nativeToolCalls.set(parsedTurn.calls.map { OrionModelToolCall(it.id, it.name, it.arguments, it.available, it.validationError, it.thoughtSignature) })
            }
            val content = parsedTurn.text
            if (truncated) {
                if (content.isBlank()) {
                    throw AiException("${p.label}: model reached its output-token limit without producing readable text.")
                }
                if (continuationDepth >= 2) {
                    throw AiException("${p.label}: response still reached its output-token limit after two continuations. The partial answer was not accepted as complete.")
                }
                onStatus?.invoke("${p.label}: response reached its output-token limit; continuing the same answer without repeating completed work...")
                val continuation = JSONArray(messages.toString())
                    .put(JSONObject().put("role", "assistant").put("content", content))
                    .put(JSONObject().put("role", "user").put("content",
                        "Continue exactly where your previous response stopped. Do not repeat earlier text, restart the answer, or change the task. Finish the response completely and do not claim completion for work not performed."))
                val remainder = chatOne(
                    p, continuation, vision, maxTokens, temperature, hasBackup,
                    fastResponse, onDelta, continuationDepth + 1, payloadRecoveryDepth
                )
                return content + remainder
            }
            if ((content.isBlank() || content == "null") && parsedTurn.calls.isEmpty()) {
                lastNote = "$model -> empty reply"
                // A selected catalog model can return HTTP 200 but no usable text.
                // Allow one bounded alternate-model attempt even in quota-saver mode;
                // never loop through the entire catalog or retry the same model.
                if (Prefs.autoSwitch(ctx) && !refilled) {
                    refilled = true
                    val backups = candidates(p, vision).filter { it != model && it !in tried }
                    queue.addAll(0, backups)
                    if (backups.isNotEmpty()) {
                        onStatus?.invoke("${p.label} returned empty content; trying one alternate model...")
                        continue
                    }
                }
                throw AiException(
                    if (Prefs.autoSwitch(ctx)) p.label + ": model returned an empty response. Auto-switch may try another eligible model or provider."
                    else p.label + ": selected model $model returned an empty response; Auto-switch is off, so Orion kept the selected model.",
                    502
                )
            }
            Prefs.setModel(ctx, p, vision, model)
            // Decision log: record this model call and its verified outcome (Requirement 12)
            val decisionId = OrionModelDecisionStore.get(ctx).record(
                tool = "model_chat",
                arguments = "provider=${p.id}; model=$model; vision=$vision; maxTokens=$maxTokens; temperature=$temperature",
                reason = "Model answered ${if (vision) "vision" else "chat"} turn",
                provider = p.id,
                model = model
            )
            OrionModelDecisionStore.get(ctx).complete(
                id = decisionId,
                result = content,
                verification = if (content.isNotBlank()) "PASS" else "NOT_VERIFIED"
            )
            // Activity timeline integration: surface the model decision in the activity timeline
            try {
                OrionConversationRepository.get(ctx).recordEvent(
                    context = ctx,
                    messageId = null,
                    type = "model_decision",
                    status = if (content.isNotBlank()) "completed" else "failed",
                    summary = "Model ${p.id} / $model ${if (content.isNotBlank()) "answered" else "returned empty"} · ${if (content.isNotBlank()) "PASS" else "NOT_VERIFIED"}",
                    correlationId = decisionId.takeIf { it.isNotBlank() }
                )
            } catch (_: Exception) { }
            return content
        }
        val kind = if (vision) "vision" else "chat"
        throw AiException("${p.label}: no $kind model worked. Last: $lastNote", lastHttpStatus)
    }
}
