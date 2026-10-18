package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the chosen AI service. If a model is gone or not allowed, it tries others. */
class Nim(private val ctx: Context) {
    private val bad = listOf(
        "embed", "guard", "rerank", "reward", "clip", "parse", "safety", "retriev", "bge",
        "whisper", "tts", "asr", "nemoguard", "riva", "fastpitch", "diffusion", "stable",
        "flux", "sdxl", "bria", "cosmos", "grounding", "segment", "ocr", "detect", "nvclip",
        "image", "imagen", "veo", "live", "transcribe", "translate", "audio", "moderation", "orpheus"
    )
    private val visionKeys = listOf(
        "vision", "-vl", "vila", "pixtral", "llava", "paligemma", "multimodal",
        "llama-4", "gemma-3", "gemini"
    )
    private val preferred = listOf(
        "llama-3.3-nemotron", "llama-3.3-70b", "llama-3.1-nemotron", "llama-3.1-70b",
        "llama-4", "qwen", "mistral-large", "mixtral", "llama-3.1-8b", "gemma", "mistral", "phi"
    )
    private val fallbacks: Map<String, Pair<List<String>, List<String>>> = mapOf(
        "nvidia" to Pair(
            listOf(
                "nvidia/nemotron-3.5-super-vl-120b-a12b", "ibm/granite-8b-code-instruct",
                "meta/llama-3.3-70b-instruct", "meta/llama-3.1-70b-instruct", "meta/llama-3.1-8b-instruct",
                "nvidia/llama-3.1-nemotron-70b-instruct", "qwen/qwen2.5-7b-instruct",
                "mistralai/mistral-7b-instruct-v0.3", "google/gemma-2-9b-it"
            ),
            listOf(
                "nvidia/nemotron-3.5-super-vl-120b-a12b", "meta/llama-3.2-90b-vision-instruct",
                "meta/llama-3.2-11b-vision-instruct", "microsoft/phi-3.5-vision-instruct"
            )
        ),
        "gemini" to Pair(
            listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite"),
            listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite")
        ),
        "groq" to Pair(
            listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "openai/gpt-oss-120b", "openai/gpt-oss-20b"),
            listOf("meta-llama/llama-4-scout-17b-16e-instruct", "meta-llama/llama-4-maverick-17b-128e-instruct")
        ),
        "openrouter" to Pair(
            listOf(
                "meta-llama/llama-3.3-70b-instruct:free", "mistralai/mistral-small-3.1-24b-instruct:free",
                "qwen/qwen-2.5-72b-instruct:free"
            ),
            listOf("google/gemma-3-27b-it:free", "qwen/qwen2.5-vl-72b-instruct:free", "mistralai/mistral-small-3.1-24b-instruct:free")
        )
    )

    private class Http(val code: Int, val body: String)

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

    private fun request(p: Provider, method: String, path: String, body: String?, key: String, readMs: Int = 50000): Http {
        val conn = URL(p.base + path).openConnection() as HttpURLConnection
        active = conn
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = readMs
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Http(code, text)
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
        val r = try {
            request(p, "GET", "/models", null, key, 25000)
        } catch (e: java.io.IOException) {
            throw RuntimeException("Cannot reach ${p.label}. Check your internet connection. (${e.javaClass.simpleName})")
        }
        if (keyBad(r)) throw RuntimeException("${p.label} rejected the key (${r.code}). Make a new key and save it again.")
        if (r.code !in 200..299) throw RuntimeException("Could not list models: HTTP ${r.code} ${r.body.take(120)}")
        val arr = JSONObject(r.body).optJSONArray("data") ?: return emptyList()
        val ids = ArrayList<String>()
        for (i in 0 until arr.length()) ids.add(arr.getJSONObject(i).optString("id").removePrefix("models/"))
        return ids.filter { it.isNotBlank() }.sorted()
    }

    fun isVision(id: String): Boolean = visionKeys.any { id.lowercase().contains(it) }

    private fun candidates(p: Provider, vision: Boolean): List<String> {
        val ids = try {
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

    fun chat(messages: JSONArray, vision: Boolean, maxTokens: Int = 600, temperature: Double = 0.2): String {
        val p = Prefs.provider(ctx, vision)
        val key = Prefs.key(ctx, p.id)
        if (key.isEmpty()) {
            throw IllegalStateException("No ${p.label} key saved. Open Phone AI > Add or change an API key.")
        }
        // Gemini models "think" first and that uses up the token budget, so leave room.
        val tokens = if (p.id == "gemini") maxOf(maxTokens * 4, 1024) else maxTokens
        val queue = mutableListOf(Prefs.model(ctx, p, vision))
        val tried = HashSet<String>()
        var refilled = false
        var lastNote = ""
        var attempts = 0
        val start = System.currentTimeMillis()
        while (queue.isNotEmpty() && attempts < 15 && System.currentTimeMillis() - start < 180000) {
            if (cancelled) throw RuntimeException("Stopped.")
            val model = queue.removeAt(0)
            if (!tried.add(model)) continue
            attempts++
            onStatus?.invoke(p.label + ": asking " + model.substringAfterLast('/') + "...")
            val payload = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", temperature)
                .put("max_tokens", tokens)
            val r: Http? = try {
                request(p, "POST", "/chat/completions", payload.toString(), key)
            } catch (e: java.net.SocketTimeoutException) {
                null
            } catch (e: java.io.IOException) {
                if (cancelled) throw RuntimeException("Stopped.")
                throw RuntimeException("Cannot reach ${p.label}. Check your internet connection. (${e.javaClass.simpleName})")
            }
            if (r == null) {
                lastNote = "$model -> too slow (no answer in 50s)"
                if (!refilled) {
                    refilled = true
                    onStatus?.invoke("That model is slow. Looking for another...")
                    queue.addAll(candidates(p, vision))
                }
                continue
            }
            if (keyBad(r)) {
                throw RuntimeException("${p.label} rejected the key (${r.code}). Make a new key and save it again.")
            }
            if (r.code == 429) {
                throw RuntimeException("${p.label} says you hit the free limit (429). Wait a minute, or switch AI in Phone AI.")
            }
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
            if (r.code !in 200..299) {
                throw RuntimeException("${p.label} error ${r.code}: ${r.body.take(160)}")
            }
            val content = try {
                JSONObject(r.body).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "")
            } catch (e: Exception) {
                ""
            }
            if (content.isBlank() || content == "null") {
                lastNote = "$model -> empty reply"
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
        throw RuntimeException("No ${p.label} $kind model worked after $attempts tries. Last: $lastNote")
    }
}
