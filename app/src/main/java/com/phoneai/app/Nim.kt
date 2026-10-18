package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the NVIDIA NIM API. If a model has been retired it finds another one that works. */
class Nim(private val ctx: Context) {
    private val base = "https://integrate.api.nvidia.com/v1"
    private val bad = listOf(
        "embed", "guard", "rerank", "reward", "clip", "parse", "safety",
        "retriev", "bge", "whisper", "tts", "asr"
    )
    private val visionKeys = listOf("vision", "-vl", "vila", "pixtral", "llava", "paligemma", "multimodal")
    private val preferred = listOf(
        "llama-3.3-nemotron", "llama-3.3-70b", "llama-3.1-nemotron", "llama-3.1-70b",
        "qwen", "mistral-large", "mixtral", "llama-3.1-8b", "gemma"
    )

    private class Http(val code: Int, val body: String)

    private fun request(method: String, path: String, body: String?, key: String): Http {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20000
            conn.readTimeout = 90000
            conn.setRequestProperty("Authorization", "Bearer $key")
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

    private fun candidates(vision: Boolean, key: String): List<String> {
        val r = request("GET", "/models", null, key)
        if (r.code !in 200..299) return emptyList()
        val arr = JSONObject(r.body).optJSONArray("data") ?: return emptyList()
        val ids = ArrayList<String>()
        for (i in 0 until arr.length()) {
            ids.add(arr.getJSONObject(i).optString("id"))
        }
        val ok = ids.filter { id -> bad.none { id.lowercase().contains(it) } }
        val vis = ok.filter { id -> visionKeys.any { id.lowercase().contains(it) } }
        if (vision) return vis.take(6)
        val txt = ok.filter { it !in vis }
        val order = ArrayList<String>()
        for (p in preferred) {
            for (id in txt) {
                if (id.lowercase().contains(p) && id !in order) order.add(id)
            }
        }
        for (id in txt) {
            if (id !in order) order.add(id)
        }
        return order.take(6)
    }

    fun chat(messages: JSONArray, vision: Boolean, maxTokens: Int = 600, temperature: Double = 0.2): String {
        val key = Prefs.key(ctx)
        if (key.isEmpty()) {
            throw IllegalStateException("No NVIDIA key saved. Open the Phone AI app and save your key.")
        }
        val queue = mutableListOf(if (vision) Prefs.visionModel(ctx) else Prefs.textModel(ctx))
        val tried = HashSet<String>()
        var refilled = false
        while (queue.isNotEmpty()) {
            val model = queue.removeAt(0)
            if (!tried.add(model)) continue
            val payload = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", temperature)
                .put("max_tokens", maxTokens)
            val r = request("POST", "/chat/completions", payload.toString(), key)
            val modelGone = r.code == 404 || r.code == 410 ||
                (r.code == 400 && r.body.lowercase().contains("model"))
            if (modelGone) {
                if (!refilled) {
                    refilled = true
                    queue.addAll(candidates(vision, key))
                }
                continue
            }
            if (r.code !in 200..299) {
                throw RuntimeException("NVIDIA error ${r.code}: ${r.body.take(200)}")
            }
            if (vision) Prefs.setVisionModel(ctx, model) else Prefs.setTextModel(ctx, model)
            return JSONObject(r.body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
        throw RuntimeException("No working NVIDIA model found. Check your key.")
    }
}
