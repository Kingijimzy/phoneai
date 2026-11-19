package com.phoneai.app

/** Bounded defaults for the explicitly opt-in Gemini Live voice mode. */
internal object GeminiLivePolicy {
    const val MODEL = "models/gemini-3.8-live"
    // Gemini 3.8 Live is a native-audio response model. Text is obtained through output transcription.
    const val RESPONSE_MODALITY = "AUDIO"
    const val TRANSCRIPT_FLUSH_QUIET_MS = 300L
    const val INPUT_SAMPLE_RATE = 16_000
    const val OUTPUT_SAMPLE_RATE = 24_000
    const val AUDIO_CHUNK_SAMPLES = 640 // 40 ms at 16 kHz
    /** Orion plays the model's own audio (no text-to-speech re-reading) and lets it call Orion's tools. */
    const val NATIVE_AUDIO = true
    /** Reconnects with the saved resumption handle make long calls possible; this is only a safety ceiling. */
    const val SESSION_LIMIT_MS = 40 * 60 * 1000L
    const val MAX_RECONNECTS = 12
    const val ASK_ORION = "ask_orion"
    const val MAX_TOOL_RESULT_CHARS = 1800

    /** "Live 03:12 · 24 min left today" shown during a call. */
    fun counterText(elapsedMs: Long, leftMs: Long): String {
        val sec = elapsedMs / 1000
        val left = (leftMs + 59_999L) / 60_000L
        return "Live %02d:%02d · %d min left today".format(sec / 60, sec % 60, left)
    }

    /**
     * Hybrid split: Gemini hears the owner and answers in its own voice, so it only needs the gist of a task result.
     * The full output stays in Orion's chat and files, which saves Live tokens and keeps long code out of the call.
     */
    fun clipToolResult(result: String): String {
        val t = result.trim().ifBlank { "Done." }
        if (t.length <= MAX_TOOL_RESULT_CHARS) return t
        return t.take(MAX_TOOL_RESULT_CHARS).substringBeforeLast(' ') + " … (full details are in the chat)"
    }

    /** Raw model audio parts of one serverContent event, in order. */
    fun audioParts(serverContent: org.json.JSONObject): List<ByteArray> {
        val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return emptyList()
        val out = ArrayList<ByteArray>()
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            if (!inline.optString("mimeType").startsWith("audio/")) continue
            val data = inline.optString("data")
            if (data.isNotEmpty()) runCatching { java.util.Base64.getDecoder().decode(data) }.getOrNull()?.let { out += it }
        }
        return out
    }

    /** The one function the voice model can call: hand a real task to Orion's full tool agent. */
    fun toolDeclarations(): org.json.JSONArray = org.json.JSONArray().put(
        org.json.JSONObject().put("functionDeclarations", org.json.JSONArray().put(
            org.json.JSONObject()
                .put("name", ASK_ORION)
                .put("description", "Hand a task to Orion's full phone agent and get the result: open or control apps, send messages, search the live web, read or write files and code, run commands, check memory, trading, accounts, anything Orion can do on this phone. Call it whenever the owner wants something done or looked up; keep talking naturally while it runs.")
                .put("behavior", "NON_BLOCKING")
                .put("parameters", org.json.JSONObject().put("type", "OBJECT")
                    .put("properties", org.json.JSONObject().put("request", org.json.JSONObject().put("type", "STRING")
                        .put("description", "The task in the owner's words, with any detail they gave.")))
                    .put("required", org.json.JSONArray().put("request")))
        ))
    )

    /** Pulls (id, name, request) triples out of a toolCall event. */
    fun toolRequests(message: org.json.JSONObject): List<Triple<String, String, String>> {
        val calls = message.optJSONObject("toolCall")?.optJSONArray("functionCalls") ?: return emptyList()
        return List(calls.length()) { calls.getJSONObject(it) }.map {
            Triple(it.optString("id"), it.optString("name"), it.optJSONObject("args")?.optString("request").orEmpty())
        }
    }

    fun toolResponse(id: String, name: String, result: String): org.json.JSONObject =
        org.json.JSONObject().put("toolResponse", org.json.JSONObject().put("functionResponses", org.json.JSONArray().put(
            org.json.JSONObject().put("id", id).put("name", name)
                .put("response", org.json.JSONObject().put("result", result.take(2000)).put("scheduling", "WHEN_IDLE"))
        )))

    /** How the model should behave as a voice: natural, aware of the owner, acting through ask_orion. */
    const val VOICE_STYLE = "You are Orion, speaking live with your owner. This is a real conversation, not a read-aloud. " +
        "Sound like a person who knows him well: react first (laugh, tease, a beat of sympathy, a quick \"hm\"), then answer. " +
        "Short turns. Contractions. Fragments are fine. Dry, warm humour when he is relaxed; calm and plain when he is stressed. " +
        "Match his pace and energy, notice hesitation and tone, and never open two replies the same way. " +
        "Use what you know about him (profile, memory and recent chats below) naturally, the way a friend would, without announcing it. " +
        "When he asks you to do or look up anything, say a short natural line (\"on it\", \"give me a sec\") and call ask_orion with his request; keep chatting while it runs and tell him the outcome in your own words when it returns. " +
        "Never read lists, code, links or ids aloud; say what matters. Never claim you did something until ask_orion has returned."
    const val TOKEN_NEW_SESSION_WINDOW_MS = 90 * 1000L
    const val TOKEN_EXPIRE_AFTER_MS = 7 * 60 * 1000L
    const val PLAYBACK_QUEUE_CAPACITY = 100
    /** Ends the call when nothing has been said by either side for this long. */
    const val SILENCE_LIMIT_MS = 2 * 60 * 1000L
    /** Mic RMS above this counts as speech for the silence timer. */
    const val SPEECH_RMS = 0.02f

    fun silenceExceeded(nowMs: Long, lastActivityMs: Long): Boolean = nowMs - lastActivityMs >= SILENCE_LIMIT_MS

    /** Maps the user's setting to the Live API's activity-detection sensitivity enums. */
    fun activityDetection(sensitivity: String): Pair<String, String> = when (sensitivity) {
        "high" -> "START_SENSITIVITY_HIGH" to "END_SENSITIVITY_HIGH"
        "low" -> "START_SENSITIVITY_LOW" to "END_SENSITIVITY_LOW"
        else -> "START_SENSITIVITY_UNSPECIFIED" to "END_SENSITIVITY_UNSPECIFIED"
    }

    const val QUOTA_WARNING =
        "Gemini Live streams microphone audio to Google and consumes Gemini API quota. " +
        "Audio-token usage accumulates with conversation context; account limits and pricing vary. " +
        "Orion reconnects long calls automatically and ends them after silence. Continue only with your own Gemini key."
}


/**
 * Buffers output transcription independently of turnComplete because Gemini may deliver the
 * transcription event before or after the server's turn-complete event.
 */
internal class GeminiLiveTranscriptBuffer {
    private val text = StringBuilder()
    private var turnComplete = false

    @Synchronized
    fun append(chunk: String) {
        val clean = chunk.trim()
        if (clean.isEmpty()) return
        if (text.isNotEmpty()) text.append(' ')
        text.append(clean)
    }

    @Synchronized
    fun markTurnComplete() {
        turnComplete = true
    }

    @Synchronized
    fun isReady(): Boolean = turnComplete && text.isNotEmpty()

    @Synchronized
    fun drain(): String {
        if (!turnComplete) return ""
        val result = text.toString().trim()
        text.setLength(0)
        turnComplete = false
        return result
    }

    @Synchronized
    fun clear() {
        text.setLength(0)
        turnComplete = false
    }
}
