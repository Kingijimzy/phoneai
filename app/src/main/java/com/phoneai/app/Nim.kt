package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the NVIDIA NIM API. If a model is gone or not allowed, it tries others. */
class Nim(private val ctx: Context) {
    private val base = "https://integrate.api.nvidia.com/v1"
    private val bad = listOf(
        "embed", "guard", "rerank", "reward", "clip", "parse", "safety", "retriev", "bge",
        "whisper", "tts", "asr", "nemoguard", "riva", "fastpitch", "diffusion", "stable",
        "flux", "sdxl", "bria", "cosmos", "grounding", "segment", "ocr", "detect", "nvclip"
    )
    private val visionKeys = listOf("vision", "-vl", "vila", "pixtral", "llava", "paligemma", "multimodal", "phi-3.5-vision", "phi-4-multimodal")
    private val preferred = listOf(
        "llama-3.3-nemotron", "llama-3.3-70b", "llama-3.1-nemotron", "llama-3.1-70b",
        "llama-4", "qwen", "mistral-large", "mixtral", "llama-3.1-8b", "gemma", "mistral", "phi"
    )
    private val fallbackText = listOf(
        "meta/llama-3.3-70b-instruct", "meta/llama-3.1-70b-instruct", "meta/llama-3.1-8b-instruct",
        "nvidia/llama-3.1-nemotron-70b-instruct", "nvidia/llama-3.3-nemotron-super-49b-v1",
        "qwen/qwen2.5-72b-instruct", "qwen/qwen2.5-7b-instruct", "mistralai/mistral-7b-instruct-v0.3",
        "mistralai/mixtral-8x22b-instruct-v0.1", "google/gemma-2-9b-it", "microsoft/phi-3-medium-128k-instruct"
    )
    private val fallbackVision = listOf(
        "meta/llama-3.2-90b-vision-instruct", "meta/llama-3.2-11b-vision-instruct",
        "microsoft/phi-3.5-vision-instruct", "nvidia/vila", "meta/llama-4-maverick-17b-128e-instruct",
        "meta/llama-4-scout-17b-16e-instruct"
    )

    private class Http(val code: Int, val body: String)

    private fun request(method: String, path: String, body: String?, key: String): Http {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20000
            conn.readTimeout = 90000
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

    /** Every model id the key can see, unfiltered. Throws with a readable message on failure. */
    fun allModels(): List<String> {
        val key = Prefs.key(ctx)
        if (key.isEmpty()) throw IllegalStateException("No NVIDIA key saved.")
        val r = request("GET", "/models", null, key)
        if (r.code == 401 || r.code == 403) throw RuntimeException("NVIDIA rejected the key (${r.code}). Make a new key and save it again.")
        if (r.code !in 200..299) throw RuntimeException("Could not list models: HTTP ${r.code} ${r.body.take(120)}")
        val arr = JSONObject(r.body).optJSONArray("data") ?: return emptyList()
        val ids = ArrayList<String>()
        for (i in 0 until arr.length()) ids.add(arr.getJSONObject(i).optString("id"))
        return ids.filter { it.isNotBlank() }.sorted()
    }

    fun isVision(id: String): Boolean = visionKeys.any { id.lowercase().contains(it) }

    private fun candidates(vision: Boolean): List<String> {
        val ids = try {
            allModels()
        } catch (e: Exception) {
            emptyList()
        }
        val ok = ids.filter { id -> bad.none { id.lowercase().contains(it) } }
        val vis = ok.filter { isVision(it) }
        val out = ArrayList<String>()
        if (vision) {
            out.addAll(vis)
            out.addAll(fallbackVision)
        } else {
            val txt = ok.filter { it !in vis }
            for (p in preferred) {
                for (id in txt) {
                    if (id.lowercase().contains(p) && id !in out) out.add(id)
                }
            }
            for (id in txt) {
                if (id !in out) out.add(id)
            }
            out.addAll(fallbackText)
        }
        return out.distinct()
    }

    fun chat(messages: JSONArray, vision: Boolean, maxTokens: Int = 600, temperature: Double = 0.2): String {
        val key = Prefs.key(ctx)
        if (key.isEmpty()) {
            throw IllegalStateException("No NVIDIA key saved. Open the Phone AI app and save your key.")
        }
        val queue = mutableListOf(if (vision) Prefs.visionModel(ctx) else Prefs.textModel(ctx))
        val tried = HashSet<String>()
        var refilled = false
        var lastNote = ""
        var attempts = 0
        while (queue.isNotEmpty() && attempts < 25) {
            val model = queue.removeAt(0)
            if (!tried.add(model)) continue
            attempts++
            val payload = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", temperature)
                .put("max_tokens", maxTokens)
            val r = request("POST", "/chat/completions", payload.toString(), key)
            if (r.code == 401 || r.code == 403) {
                throw RuntimeException("NVIDIA rejected the key (${r.code}). Make a new key at build.nvidia.com and save it again.")
            }
            if (r.code == 429) {
                throw RuntimeException("NVIDIA says too many requests (429). Wait a minute and try again.")
            }
            val modelProblem = r.code == 404 || r.code == 410 || r.code == 422 ||
                (r.code == 400 && r.body.isNotEmpty())
            if (modelProblem) {
                lastNote = "$model -> ${r.code} ${r.body.replace("\n", " ").take(100)}"
                if (!refilled) {
                    refilled = true
                    queue.addAll(candidates(vision))
                }
                continue
            }
            if (r.code !in 200..299) {
                throw RuntimeException("NVIDIA error ${r.code}: ${r.body.take(160)}")
            }
            if (vision) Prefs.setVisionModel(ctx, model) else Prefs.setTextModel(ctx, model)
            return JSONObject(r.body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
        val kind = if (vision) "vision" else "text"
        throw RuntimeException("No $kind model worked after $attempts tries. Last: $lastNote. Open Phone AI > Choose AI model and pick one yourself.")
    }
}
