package com.phoneai.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional online chat voice: Gemini speech generation using the Gemini key already saved for Orion.
 * Opt-in only (the reply text goes to Google). Any failure falls back to the installed local voice, so Orion never goes silent.
 * Returns 24 kHz mono 16-bit PCM.
 */
internal object OrionGeminiVoice {
    const val MODEL = "gemini-3.8-flash-tts"
    const val SAMPLE_RATE = 24_000

    fun available(c: Context): Boolean = Prefs.geminiChatVoice(c) && Prefs.key(c, "gemini").isNotBlank()

    internal fun requestBody(text: String, voice: String, style: String = "warm, calm, natural, conversational"): String =
        JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(
                JSONObject().put("text", text).put("speech_metadata", JSONObject().put("style", style))))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("voice", voice)))
                .put("responseFormat", JSONObject().put("audio", JSONObject().put("mimeType", "AUDIO_L16").put("sampleRate", SAMPLE_RATE))))
            .toString()

    /** Pulls raw PCM out of a generateContent response; tolerates a WAV header in case the format hint is ignored. */
    internal fun pcmFrom(response: String): ByteArray? = runCatching {
        val data = JSONObject(response).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
        var pcm = Base64.decode(data, Base64.DEFAULT)
        if (pcm.size > 44 && String(pcm, 0, 4, Charsets.US_ASCII) == "RIFF") pcm = pcm.copyOfRange(44, pcm.size)
        pcm.takeIf { it.size >= 2 }
    }.getOrNull()

    /** 16-bit little-endian PCM to floats, the format Orion's shared audio track plays. */
    internal fun toFloats(pcm: ByteArray): FloatArray = FloatArray(pcm.size / 2) { i ->
        ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort() / 32768f
    }

    /** One sentence group to audio, or null if Gemini could not do it right now. Blocking: call from a worker thread. */
    fun synthesize(c: Context, text: String): FloatArray? = runCatching {
        val key = Prefs.key(c, "gemini")
        val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 6_000; readTimeout = 20_000; doOutput = true
            setRequestProperty("x-goog-api-key", key); setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(requestBody(text, Prefs.geminiVoice(c)).toByteArray()) }
        if (conn.responseCode !in 200..299) return@runCatching null
        pcmFrom(conn.inputStream.bufferedReader().readText())?.let { toFloats(it) }
    }.getOrNull()
}
